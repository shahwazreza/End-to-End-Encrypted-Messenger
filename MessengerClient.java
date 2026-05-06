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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocketFactory;

public class MessengerClient {

    private static final Logger log = Logger.getLogger(MessengerClient.class.getName());

    private final String  username;
    private final String  password;
    private final String  host;
    private final int     port;
    private final boolean useTls;

    private Socket     socket;
    private PrintWriter out;

    private volatile String    peer;
    private volatile SecretKey shared;
    private volatile CompletableFuture<String> pendingKey;

    private Runnable               onAuthSuccess;
    private Consumer<List<String>> onUsersUpdated;
    private Consumer<String>       onUserJoined;
    private Consumer<String>       onUserLeft;
    private Runnable               onChatReady;
    private Consumer<String>       onMessageReceived;
    private Consumer<String>       onError;
    private Consumer<String>       onStatus;
    private Runnable               onDisconnected;

    public MessengerClient(String username, String password, String host, int port, boolean useTls) {
        this.username = username;
        this.password = password;
        this.host     = host;
        this.port     = port;
        this.useTls   = useTls;
    }

    public void setOnAuthSuccess(Runnable h)                { this.onAuthSuccess     = h; }
    public void setOnUsersUpdated(Consumer<List<String>> h) { this.onUsersUpdated    = h; }
    public void setOnUserJoined(Consumer<String> h)         { this.onUserJoined      = h; }
    public void setOnUserLeft(Consumer<String> h)           { this.onUserLeft        = h; }
    public void setOnChatReady(Runnable h)                  { this.onChatReady       = h; }
    public void setOnMessageReceived(Consumer<String> h)    { this.onMessageReceived = h; }
    public void setOnError(Consumer<String> h)              { this.onError           = h; }
    public void setOnStatus(Consumer<String> h)             { this.onStatus          = h; }
    public void setOnDisconnected(Runnable h)               { this.onDisconnected    = h; }

    public String getUsername() { return username; }
    public String getPeer()     { return peer; }

    // ── Connect & authenticate ────────────────────────────────────────────────

    public void connectAsync(boolean register) {
        Thread t = new Thread(() -> {
            try { connectAndAuth(register); }
            catch (Exception e) { if (onError != null) onError.accept(userFriendlyError(e)); }
        });
        t.setDaemon(true);
        t.start();
    }

    private void connectAndAuth(boolean register) throws Exception {
        fireStatus("Connecting" + (useTls ? " with TLS..." : "..."));
        socket = createSocket();
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(socket.getOutputStream(), true);

        String hello = in.readLine();
        if (!"OK|CONNECTED".equals(hello)) throw new IOException("Unexpected server greeting");

        out.println((register ? "REGISTER_ACCOUNT" : "LOGIN") + "|" + username + "|" + password);

        String resp = in.readLine();
        if (resp == null) throw new EOFException("Server closed during auth");
        if (resp.startsWith("ERROR|")) throw new IOException(resp.substring(6));
        if (!"OK|AUTH".equals(resp)) throw new IOException("Unexpected auth response");

        fireStatus(register ? "Account created — welcome, " + username : "Signed in as " + username);
        if (onAuthSuccess != null) onAuthSuccess.run();

        // ── Read loop ─────────────────────────────────────────────────────────
        String msg;
        while ((msg = in.readLine()) != null) {
            if (msg.startsWith("USERS|")) {
                String body = msg.substring(6);
                List<String> users = body.isEmpty()
                        ? new ArrayList<>()
                        : new ArrayList<>(Arrays.asList(body.split(",")));
                users.remove(username);
                if (onUsersUpdated != null) onUsersUpdated.accept(users);

            } else if (msg.startsWith("USER_JOINED|")) {
                String who = msg.substring(12);
                if (!who.equals(username) && onUserJoined != null) onUserJoined.accept(who);

            } else if (msg.startsWith("USER_LEFT|")) {
                if (onUserLeft != null) onUserLeft.accept(msg.substring(10));

            } else if (msg.startsWith("KEY|")) {
                CompletableFuture<String> f = pendingKey;
                if (f != null) f.complete(msg.substring(4));

            } else if (msg.startsWith("MSG|")) {
                String[] parts = msg.split("\\|", 3);
                if (parts.length == 3 && parts[1].equals(peer) && shared != null) {
                    handleEncryptedMessage(parts[2]);
                }

            } else if (msg.startsWith("ERROR|")) {
                fireStatus(msg.substring(6));
            }
        }

        fireStatus("Disconnected");
        if (onDisconnected != null) onDisconnected.run();
    }

    // ── Key exchange & chat ───────────────────────────────────────────────────

    public void startChatWith(String targetPeer) {
        this.peer   = targetPeer;
        this.shared = null;

        Thread t = new Thread(() -> {
            try {
                boolean fresh = !KeyManager.hasStoredIdentity(username);
                KeyPair myKeys = KeyManager.loadOrCreate(username);
                fireStatus(fresh ? "Generated new identity" : "Loaded existing identity");

                out.println("PUBKEY|" + Base64.getEncoder().encodeToString(myKeys.getPublic().getEncoded()));

                fireStatus("Waiting for " + targetPeer + "...");
                long   deadline   = System.currentTimeMillis() + 30_000;
                String peerKeyB64 = null;

                while (true) {
                    if (System.currentTimeMillis() > deadline)
                        throw new Exception("Timed out waiting for " + targetPeer);
                    pendingKey = new CompletableFuture<>();
                    out.println("GETKEY|" + targetPeer);
                    try { peerKeyB64 = pendingKey.get(2, TimeUnit.SECONDS); }
                    catch (Exception ignored) {}
                    if (peerKeyB64 != null && !"null".equals(peerKeyB64)) break;
                    Thread.sleep(500);
                }
                pendingKey = null;

                PublicKey peerPub = KeyFactory.getInstance("X25519")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(peerKeyB64)));
                shared = KeyDerivation.deriveSharedKey(myKeys.getPrivate(), peerPub);

                fireStatus("Secure channel established with " + targetPeer);
                if (onChatReady != null) onChatReady.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void leaveChat() {
        this.peer   = null;
        this.shared = null;
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    public void sendMessage(String text) throws Exception {
        if (out == null || shared == null) throw new IllegalStateException("Not connected");
        Encryption.EncryptedData enc = Encryption.encrypt(text, shared);
        byte[] combined = new byte[enc.iv.length + enc.ciphertext.length];
        System.arraycopy(enc.iv,         0, combined, 0,             enc.iv.length);
        System.arraycopy(enc.ciphertext, 0, combined, enc.iv.length, enc.ciphertext.length);
        out.println(peer + "|" + Base64.getEncoder().encodeToString(combined));
        if (out.checkError()) throw new IOException("Failed to send message");
        MessageHistory.append(username, peer, true, text);
    }

    // ── Receive ──────────────────────────────────────────────────────────────

    private void handleEncryptedMessage(String payload) {
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length <= 12) { fireStatus("Malformed message received"); return; }
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

    // ── Disconnect ───────────────────────────────────────────────────────────

    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void fireStatus(String msg) {
        if (onStatus != null) onStatus.accept(msg);
    }

    private String userFriendlyError(Exception e) {
        String m = e.getMessage();
        if (m != null && m.contains("Unsupported or unrecognized SSL message"))
            return "TLS mismatch: server is not using TLS.";
        return m != null ? m : e.getClass().getSimpleName();
    }

    private Socket createSocket() throws IOException {
        return useTls
                ? SSLSocketFactory.getDefault().createSocket(host, port)
                : new Socket(host, port);
    }
}
