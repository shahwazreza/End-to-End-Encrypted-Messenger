import crypto.Encryption;
import crypto.KeyDerivation;
import crypto.KeyManager;

import javax.crypto.SecretKey;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class MessengerClient {

    private static final Logger log = Logger.getLogger(MessengerClient.class.getName());

    private final String username;
    private final String password;
    private final String host;
    private final int port;
    private final boolean useTls;

    private Socket socket;
    private PrintWriter out;
    private KeyPair keyPair;

    private volatile String peer;
    private final Set<String> onlineUsers = ConcurrentHashMap.newKeySet();
    private final Set<String> knownUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, SecretKey> sharedKeys = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> pendingKeys = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sendSeq = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> recvSeq = new ConcurrentHashMap<>();

    private Runnable onAuthSuccess;
    private Consumer<List<String>> onUsersUpdated;
    private Consumer<String> onUserJoined;
    private Consumer<String> onUserLeft;
    private Runnable onChatReady;
    private Consumer<String> onMessageReceived;
    private BiConsumer<String, String> onMessageReceivedFrom;
    private Consumer<String> onMessageQueued;
    private Consumer<String> onNotification;
    private Consumer<String> onError;
    private Consumer<String> onStatus;
    private Runnable onDisconnected;

    public MessengerClient(String username, String password, String host, int port, boolean useTls) {
        this.username = username.toLowerCase();
        this.password = password;
        this.host = host;
        this.port = port;
        this.useTls = useTls;
    }

    public void setOnAuthSuccess(Runnable h) { this.onAuthSuccess = h; }
    public void setOnUsersUpdated(Consumer<List<String>> h) { this.onUsersUpdated = h; }
    public void setOnUserJoined(Consumer<String> h) { this.onUserJoined = h; }
    public void setOnUserLeft(Consumer<String> h) { this.onUserLeft = h; }
    public void setOnChatReady(Runnable h) { this.onChatReady = h; }
    public void setOnMessageReceived(Consumer<String> h) { this.onMessageReceived = h; }
    public void setOnMessageReceivedFrom(BiConsumer<String, String> h) { this.onMessageReceivedFrom = h; }
    public void setOnMessageQueued(Consumer<String> h) { this.onMessageQueued = h; }
    public void setOnNotification(Consumer<String> h) { this.onNotification = h; }
    public void setOnError(Consumer<String> h) { this.onError = h; }
    public void setOnStatus(Consumer<String> h) { this.onStatus = h; }
    public void setOnDisconnected(Runnable h) { this.onDisconnected = h; }

    public String getUsername() { return username; }
    public String getPeer() { return peer; }

    public void connectAsync(boolean register) {
        Thread t = new Thread(() -> {
            try {
                connectAndAuth(register);
            } catch (Exception e) {
                if (onError != null) onError.accept(userFriendlyError(e));
            }
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

        boolean fresh = !KeyManager.hasStoredIdentity(username);
        keyPair = KeyManager.loadOrCreate(username);
        out.println("PUBKEY|" + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));

        fireStatus(register ? "Account created. Welcome, " + username : "Signed in as " + username);
        if (fresh) fireStatus("Generated new identity");
        if (onAuthSuccess != null) onAuthSuccess.run();

        String msg;
        while ((msg = in.readLine()) != null) {
            if (msg.startsWith("USERS|")) {
                onlineUsers.clear();
                onlineUsers.addAll(parseUsers(msg.substring(6)));
                knownUsers.addAll(onlineUsers);
                fireUsersUpdated();
            } else if (msg.startsWith("KNOWN_USERS|")) {
                knownUsers.addAll(parseUsers(msg.substring(12)));
                fireUsersUpdated();
            } else if (msg.startsWith("USER_JOINED|")) {
                String who = msg.substring(12);
                if (!who.equals(username)) {
                    onlineUsers.add(who);
                    knownUsers.add(who);
                    if (onUserJoined != null) onUserJoined.accept(who);
                    fireUsersUpdated();
                }
            } else if (msg.startsWith("USER_LEFT|")) {
                String who = msg.substring(10);
                onlineUsers.remove(who);
                if (onUserLeft != null) onUserLeft.accept(who);
                fireUsersUpdated();
            } else if (msg.startsWith("KEY|")) {
                String[] parts = msg.split("\\|", 3);
                if (parts.length == 3) {
                    CompletableFuture<String> f = pendingKeys.remove(parts[1]);
                    if (f != null) f.complete(parts[2]);
                }
            } else if (msg.startsWith("MSG|")) {
                String[] parts = msg.split("\\|", 4);
                if (parts.length == 4) handleEncryptedMessage(parts[1], parts[2], parts[3]);
            } else if (msg.startsWith("QUEUED|")) {
                if (onMessageQueued != null) onMessageQueued.accept(msg.substring(7));
            } else if (msg.startsWith("ERROR|")) {
                fireStatus(msg.substring(6));
            }
        }

        fireStatus("Disconnected");
        if (onDisconnected != null) onDisconnected.run();
    }

    public void startChatWith(String targetPeer) {
        this.peer = targetPeer.toLowerCase();
        Thread t = new Thread(() -> {
            try {
                fireStatus("Opening chat with " + peer + "...");
                sharedKeyFor(peer);
                fireStatus("Secure channel established with " + peer);
                if (onChatReady != null) onChatReady.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void refreshUsers() {
        if (out != null) {
            out.println("USERS");
            out.println("KNOWN_USERS");
        }
    }

    public void leaveChat() {
        this.peer = null;
    }

    public void sendMessage(String text) throws Exception {
        String target = peer;
        if (out == null || target == null) throw new IllegalStateException("No chat selected");
        SecretKey shared = sharedKeyFor(target);
        long seq = sendSeq.computeIfAbsent(target, k -> new AtomicLong(0)).incrementAndGet();
        Encryption.EncryptedData enc = Encryption.encrypt(seq + "\n" + text, shared);
        byte[] combined = new byte[enc.iv.length + enc.ciphertext.length];
        System.arraycopy(enc.iv, 0, combined, 0, enc.iv.length);
        System.arraycopy(enc.ciphertext, 0, combined, enc.iv.length, enc.ciphertext.length);
        out.println(target + "|" + Base64.getEncoder().encodeToString(combined));
        if (out.checkError()) throw new IOException("Failed to send message");
        MessageHistory.append(username, target, true, text);
    }

    private void handleEncryptedMessage(String sender, String senderKeyB64, String payload) {
        try {
            SecretKey shared = sharedKeyFor(sender, senderKeyB64);
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length <= 12) {
                fireStatus("Malformed message received");
                return;
            }
            Encryption.EncryptedData data = new Encryption.EncryptedData();
            data.iv = Arrays.copyOfRange(bytes, 0, 12);
            data.ciphertext = Arrays.copyOfRange(bytes, 12, bytes.length);
            String decrypted = Encryption.decrypt(data, shared);
            int nl = decrypted.indexOf('\n');
            if (nl < 0) { fireStatus("Malformed message from " + sender); return; }
            long seq;
            try { seq = Long.parseLong(decrypted.substring(0, nl)); }
            catch (NumberFormatException e) { fireStatus("Malformed message from " + sender); return; }
            long last = recvSeq.computeIfAbsent(sender, k -> new AtomicLong(0)).getAndUpdate(v -> seq > v ? seq : v);
            if (seq <= last) {
                log.warning("Rejected replayed message from " + sender + " (seq=" + seq + ", last=" + last + ")");
                return;
            }
            String text = decrypted.substring(nl + 1);
            MessageHistory.append(username, sender, false, text);

            if (sender.equals(peer) && onMessageReceived != null) {
                onMessageReceived.accept(text);
            } else if (!sender.equals(peer) && onNotification != null) {
                onNotification.accept(sender);
            }
            if (onMessageReceivedFrom != null) onMessageReceivedFrom.accept(sender, text);
        } catch (Exception e) {
            fireStatus("Could not decrypt a message from " + sender);
            log.warning("Decryption failed: " + e.getMessage());
        }
    }

    private SecretKey sharedKeyFor(String targetPeer) throws Exception {
        return sharedKeyFor(targetPeer, null);
    }

    private SecretKey sharedKeyFor(String targetPeer, String suppliedPeerKeyB64) throws Exception {
        String normalizedPeer = targetPeer.toLowerCase();
        SecretKey cached = sharedKeys.get(normalizedPeer);
        if (cached != null) return cached;
        if (keyPair == null) keyPair = KeyManager.loadOrCreate(username);

        String peerKeyB64 = suppliedPeerKeyB64;
        if (peerKeyB64 == null || peerKeyB64.equals("null")) {
            peerKeyB64 = fetchPeerKey(normalizedPeer);
        }
        PublicKey peerPub = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(peerKeyB64)));
        SecretKey derived = KeyDerivation.deriveSharedKey(keyPair.getPrivate(), peerPub);
        sharedKeys.put(normalizedPeer, derived);
        return derived;
    }

    private String fetchPeerKey(String targetPeer) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new Exception("No public key found for " + targetPeer);
            }
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingKeys.put(targetPeer, future);
            out.println("GETKEY|" + targetPeer);
            String peerKeyB64;
            try {
                peerKeyB64 = future.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                peerKeyB64 = null;
            } finally {
                pendingKeys.remove(targetPeer);
            }
            if (peerKeyB64 != null && !"null".equals(peerKeyB64)) return peerKeyB64;
            Thread.sleep(500);
        }
    }

    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    private List<String> parseUsers(String body) {
        if (body.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(body.split(",")));
    }

    private void fireUsersUpdated() {
        List<String> users = new ArrayList<>(knownUsers);
        users.remove(username);
        Collections.sort(users);
        if (onUsersUpdated != null) onUsersUpdated.accept(users);
    }

    private void fireStatus(String msg) {
        if (onStatus != null) onStatus.accept(msg);
    }

    private String userFriendlyError(Exception e) {
        String m = e.getMessage();
        if (m != null && m.contains("Unsupported or unrecognized SSL message")) {
            return "TLS mismatch: server is not using TLS.";
        }
        return m != null ? m : e.getClass().getSimpleName();
    }

    private Socket createSocket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5_000);
        if (!useTls) return s;
        try {
            String tsPath = System.getProperty("javax.net.ssl.trustStore");
            String tsPass = System.getProperty("javax.net.ssl.trustStorePassword", "changeit");
            if (tsPath != null) {
                KeyStore ts = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(Paths.get(tsPath))) {
                    ts.load(in, tsPass.toCharArray());
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tmf.getTrustManagers(), null);
                return ctx.getSocketFactory().createSocket(s, host, port, true);
            }
            return ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
    }
}
