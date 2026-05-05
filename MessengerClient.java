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
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class MessengerClient {

    private static final long PEER_KEY_TIMEOUT_MS = 30_000;
    private static final Logger log = Logger.getLogger(MessengerClient.class.getName());

    private final String username;
    private final String peer;
    private final String host;
    private final int port;

    private Socket socket;
    private PrintWriter out;
    private SecretKey shared;

    private Runnable onConnected;
    private Consumer<String> onMessageReceived;
    private Consumer<String> onError;
    private Runnable onDisconnected;

    public MessengerClient(String username, String peer, String host, int port) {
        this.username = username;
        this.peer = peer;
        this.host = host;
        this.port = port;
    }

    public void setOnConnected(Runnable handler)             { this.onConnected = handler; }
    public void setOnMessageReceived(Consumer<String> handler) { this.onMessageReceived = handler; }
    public void setOnError(Consumer<String> handler)          { this.onError = handler; }
    public void setOnDisconnected(Runnable handler)           { this.onDisconnected = handler; }
    public String getPeer() { return peer; }

    public void connectAsync() {
        Thread t = new Thread(() -> {
            try {
                connect();
            } catch (Exception e) {
                if (onError != null) onError.accept(e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void connect() throws Exception {
        socket = new Socket(host, port);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);

        out.println(username);

        KeyPair myKeys = KeyManager.generateKeyPair();
        out.println("REGISTER|" + username + "|" +
                Base64.getEncoder().encodeToString(myKeys.getPublic().getEncoded()));

        String peerKeyB64 = null;
        long deadline = System.currentTimeMillis() + PEER_KEY_TIMEOUT_MS;
        while (peerKeyB64 == null || peerKeyB64.equals("null")) {
            if (System.currentTimeMillis() >= deadline)
                throw new Exception("Timed out waiting for '" + peer + "' to connect");
            out.println("GETKEY|" + peer);
            peerKeyB64 = in.readLine();
            if ("null".equals(peerKeyB64)) {
                Thread.sleep(500);
                peerKeyB64 = null;
            }
        }

        PublicKey peerPublic = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(peerKeyB64)));
        shared = KeyDerivation.deriveSharedKey(myKeys.getPrivate(), peerPublic);

        if (onConnected != null) onConnected.run();

        String msg;
        while ((msg = in.readLine()) != null) {
            byte[] bytes = Base64.getDecoder().decode(msg);
            Encryption.EncryptedData data = new Encryption.EncryptedData();
            data.iv         = Arrays.copyOfRange(bytes, 0, 12);
            data.ciphertext = Arrays.copyOfRange(bytes, 12, bytes.length);
            try {
                String decrypted = Encryption.decrypt(data, shared);
                if (onMessageReceived != null) onMessageReceived.accept(decrypted);
            } catch (Exception e) {
                log.warning("Decryption failed: " + e.getMessage());
            }
        }

        if (onDisconnected != null) onDisconnected.run();
    }

    public void sendMessage(String text) throws Exception {
        if (out == null || shared == null) throw new IllegalStateException("Not connected");
        Encryption.EncryptedData enc = Encryption.encrypt(text, shared);
        byte[] combined = new byte[enc.iv.length + enc.ciphertext.length];
        System.arraycopy(enc.iv,         0, combined, 0,              enc.iv.length);
        System.arraycopy(enc.ciphertext, 0, combined, enc.iv.length,  enc.ciphertext.length);
        out.println(peer + "|" + Base64.getEncoder().encodeToString(combined));
    }

    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
