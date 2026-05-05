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
import java.util.logging.*;

public class Client {

    private static final String HOST = System.getProperty("server.host", "localhost");
    private static final int PORT = Integer.parseInt(System.getProperty("server.port", "5000"));
    private static final long PEER_KEY_TIMEOUT_MS = 30_000;
    private static final Logger log = Logger.getLogger(Client.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java Client <username> <peer>");
            System.exit(1);
        }

        String username = args[0].trim();
        String peer = args[1].trim();

        if (username.isEmpty() || peer.isEmpty() || username.equals(peer)) {
            System.err.println("Username and peer must be non-empty and different");
            System.exit(1);
        }

        Socket socket = new Socket(HOST, PORT);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        out.println(username);

        KeyPair myKeys = KeyManager.generateKeyPair();
        out.println("REGISTER|" + username + "|" +
                Base64.getEncoder().encodeToString(myKeys.getPublic().getEncoded()));

        // Poll for peer's public key with timeout
        String peerKeyB64 = null;
        long deadline = System.currentTimeMillis() + PEER_KEY_TIMEOUT_MS;
        while (peerKeyB64 == null || peerKeyB64.equals("null")) {
            if (System.currentTimeMillis() >= deadline) {
                System.err.println("Timed out waiting for peer '" + peer + "' to connect");
                socket.close();
                System.exit(1);
            }
            out.println("GETKEY|" + peer);
            peerKeyB64 = in.readLine();
            if ("null".equals(peerKeyB64)) {
                Thread.sleep(500);
                peerKeyB64 = null;
            }
        }

        PublicKey peerPublic = KeyFactory.getInstance("X25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(peerKeyB64)));
        SecretKey shared = KeyDerivation.deriveSharedKey(myKeys.getPrivate(), peerPublic);
        System.out.println("Secure channel established with " + peer + ". Type messages below:");

        Thread receiveThread = new Thread(() -> {
            try {
                String msg;
                while ((msg = in.readLine()) != null) {
                    byte[] bytes = Base64.getDecoder().decode(msg);
                    Encryption.EncryptedData data = new Encryption.EncryptedData();
                    data.iv = Arrays.copyOfRange(bytes, 0, 12);
                    data.ciphertext = Arrays.copyOfRange(bytes, 12, bytes.length);
                    try {
                        System.out.println("[" + peer + "]: " + Encryption.decrypt(data, shared));
                    } catch (Exception e) {
                        log.warning("Decryption failed: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (!socket.isClosed()) log.warning("Receive error: " + e.getMessage());
            }
        });
        receiveThread.setDaemon(true);
        receiveThread.start();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        String text;
        while ((text = console.readLine()) != null) {
            if (text.isBlank()) continue;
            Encryption.EncryptedData enc = Encryption.encrypt(text, shared);
            byte[] combined = new byte[enc.iv.length + enc.ciphertext.length];
            System.arraycopy(enc.iv, 0, combined, 0, enc.iv.length);
            System.arraycopy(enc.ciphertext, 0, combined, enc.iv.length, enc.ciphertext.length);
            out.println(peer + "|" + Base64.getEncoder().encodeToString(combined));
        }

        socket.close();
    }
}