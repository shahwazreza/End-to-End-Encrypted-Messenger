import crypto.Encryption;
import crypto.KeyDerivation;
import crypto.KeyManager;

import javax.crypto.SecretKey;
import java.io.*;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocketFactory;

public class MessengerClient {

    private static final long PEER_KEY_TIMEOUT_MS = 30_000;
    private static final Logger log = Logger.getLogger(MessengerClient.class.getName());

    private final String username;
    private final String peer;
    private final String host;
    private final int port;
    private final boolean useTls;

    private Socket socket;
    private PrintWriter out;
    private SecretKey shared;

    private Runnable onConnected;
    private Consumer<String> onMessageReceived;
    private Consumer<String> onError;
    private Consumer<String> onStatus;
    private Runnable onDisconnected;

    public MessengerClient(String username, String peer, String host, int port) {
        this(username, peer, host, port, false);
    }

    public MessengerClient(String username, String peer, String host, int port, boolean useTls) {
        this.username = username;
        this.peer = peer;
        this.host = host;
        this.port = port;
        this.useTls = useTls;
    }

    public void setOnConnected(Runnable handler)             { this.onConnected = handler; }
    public void setOnMessageReceived(Consumer<String> handler) { this.onMessageReceived = handler; }
    public void setOnError(Consumer<String> handler)          { this.onError = handler; }
    public void setOnStatus(Consumer<String> handler)         { this.onStatus = handler; }
    public void setOnDisconnected(Runnable handler)           { this.onDisconnected = handler; }
    public String getPeer() { return peer; }

    public void connectAsync() {
        Thread t = new Thread(() -> {
            try {
                connect();
            } catch (Exception e) {
                if (onError != null) onError.accept(userFriendlyError(e));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void connect() throws Exception {
        fireStatus("Connecting to " + host + ":" + port + (useTls ? " with TLS..." : "..."));
        socket = createSocket();
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);

        out.println(username);
        String hello = in.readLine();
        if (hello == null) throw new EOFException("Server closed the connection");
        if (hello.startsWith("ERROR|")) throw new IOException(hello.substring(6));
        if (!hello.equals("OK|CONNECTED")) throw new IOException("Unexpected server handshake response");

        boolean freshIdentity = !KeyManager.hasStoredIdentity(username);
        KeyPair myKeys = KeyManager.loadOrCreate(username);
        fireStatus(freshIdentity ? "Generated new identity for " + username : "Loaded existing identity for " + username);
        out.println("REGISTER|" + username + "|" +
                Base64.getEncoder().encodeToString(myKeys.getPublic().getEncoded()));

        String peerKeyB64 = null;
        List<String> pendingMessages = new ArrayList<>();
        long deadline = System.currentTimeMillis() + PEER_KEY_TIMEOUT_MS;
        fireStatus("Waiting for " + peer + " to connect...");
        while (peerKeyB64 == null || peerKeyB64.equals("null")) {
            if (System.currentTimeMillis() >= deadline)
                throw new Exception("Timed out waiting for '" + peer + "' to connect");
            out.println("GETKEY|" + peer);
            String response = in.readLine();
            if (response == null) throw new EOFException("Disconnected while waiting for peer key");
            if (response.startsWith("MSG|")) {
                String[] parts = response.split("\\|", 3);
                if (parts.length == 3 && parts[1].equals(peer)) {
                    pendingMessages.add(parts[2]);
                }
                continue;
            }
            if (response.startsWith("ERROR|")) {
                fireStatus(response.substring(6));
                continue;
            }
            if (!response.startsWith("KEY|")) {
                fireStatus("Ignored unexpected server response");
                continue;
            }
            peerKeyB64 = response.substring(4);
            if ("null".equals(peerKeyB64)) {
                Thread.sleep(500);
                peerKeyB64 = null;
            }
        }

        PublicKey peerPublic = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(peerKeyB64)));
        shared = KeyDerivation.deriveSharedKey(myKeys.getPrivate(), peerPublic);

        fireStatus("Secure channel established with " + peer);
        if (onConnected != null) onConnected.run();
        for (String pendingMessage : pendingMessages) {
            handleEncryptedMessage(pendingMessage);
        }

        String msg;
        while ((msg = in.readLine()) != null) {
            if (msg.startsWith("MSG|")) {
                String[] parts = msg.split("\\|", 3);
                if (parts.length == 3 && parts[1].equals(peer)) {
                    handleEncryptedMessage(parts[2]);
                }
            } else if (msg.startsWith("ERROR|")) {
                fireStatus(msg.substring(6));
            } else {
                fireStatus("Ignored unexpected server response");
            }
        }

        fireStatus("Disconnected from server");
        if (onDisconnected != null) onDisconnected.run();
    }

    public void sendMessage(String text) throws Exception {
        if (out == null || shared == null) throw new IllegalStateException("Not connected");
        Encryption.EncryptedData enc = Encryption.encrypt(text, shared);
        byte[] combined = new byte[enc.iv.length + enc.ciphertext.length];
        System.arraycopy(enc.iv,         0, combined, 0,              enc.iv.length);
        System.arraycopy(enc.ciphertext, 0, combined, enc.iv.length,  enc.ciphertext.length);
        out.println(peer + "|" + Base64.getEncoder().encodeToString(combined));
        if (out.checkError()) {
            throw new IOException("Failed to send message to server");
        }
        MessageHistory.append(username, peer, true, text);
    }

    private void handleEncryptedMessage(String payload) {
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length <= 12) {
                fireStatus("Received malformed encrypted message");
                return;
            }
            Encryption.EncryptedData data = new Encryption.EncryptedData();
            data.iv         = Arrays.copyOfRange(bytes, 0, 12);
            data.ciphertext = Arrays.copyOfRange(bytes, 12, bytes.length);
            String decrypted = Encryption.decrypt(data, shared);
            MessageHistory.append(username, peer, false, decrypted);
            if (onMessageReceived != null) onMessageReceived.accept(decrypted);
        } catch (Exception e) {
            fireStatus("Could not decrypt a message from " + peer);
            log.warning("Decryption failed: " + e.getMessage());
        }
    }

    private void fireStatus(String message) {
        if (onStatus != null) onStatus.accept(message);
    }

    private String userFriendlyError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.contains("Unsupported or unrecognized SSL message")) {
            return "TLS is enabled, but the server on " + host + ":" + port
                    + " is not using TLS. Stop the plain server and start the TLS server.";
        }
        return message != null ? message : e.getClass().getSimpleName();
    }

    private Socket createSocket() throws IOException {
        if (!useTls) {
            return new Socket(host, port);
        }
        return SSLSocketFactory.getDefault().createSocket(host, port);
    }

    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
