import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class Client {

    private static final String  HOST    = System.getProperty("server.host", "localhost");
    private static final int     PORT    = Integer.parseInt(System.getProperty("server.port", "5000"));
    private static final boolean USE_TLS = Boolean.parseBoolean(System.getProperty("client.tls", "true"));

    private static Process serverProcess;

    public static void main(String[] args) throws Exception {
        boolean register = args.length > 0 && "--register".equals(args[0]);
        String[] rest    = register ? Arrays.copyOfRange(args, 1, args.length) : args;

        if (rest.length < 3) {
            System.err.println("Usage: java Client [--register] <username> <password> <peer>");
            System.exit(1);
        }

        String username = rest[0].trim().toLowerCase();
        String password = rest[1];
        String peer     = rest[2].trim().toLowerCase();

        if (username.isEmpty() || password.isEmpty() || peer.isEmpty() || username.equals(peer)) {
            System.err.println("Username, password, and peer must be non-empty; username and peer must differ");
            System.exit(1);
        }

        boolean isLocal = HOST.equals("localhost") || HOST.equals("127.0.0.1");
        if (USE_TLS && isLocal) {
            if (isPortOpen(HOST, PORT)) {
                configureLocalTrustStore();
            } else {
                System.out.println("* Starting TLS server...");
                Path keyStore = setupLocalTls();
                startLocalTlsServer(keyStore, PORT);
            }
        }

        CountDownLatch chatReady = new CountDownLatch(1);
        MessengerClient client = new MessengerClient(username, password, HOST, PORT, USE_TLS);

        client.setOnStatus(msg -> System.out.println("* " + msg));
        client.setOnError(err -> { System.err.println("Error: " + err); System.exit(1); });
        client.setOnAuthSuccess(() -> {
            System.out.println("Authenticated. Connecting to " + peer + "...");
            client.startChatWith(peer);
        });
        client.setOnChatReady(() -> {
            System.out.println("Secure channel established with " + peer + ". Type messages below:");

            List<MessageHistory.Entry> history = MessageHistory.load(username, peer);
            if (!history.isEmpty()) {
                System.out.println("── Previous messages ──");
                for (MessageHistory.Entry e : history)
                    System.out.println("[" + e.formattedTime() + "] " + (e.sent() ? "you" : peer) + ": " + e.text());
                System.out.println("── Now ──");
            }
            chatReady.countDown();
        });
        client.setOnMessageReceived(msg -> System.out.println("[" + peer + "]: " + msg));
        client.setOnDisconnected(() -> { System.out.println("Disconnected."); System.exit(0); });

        client.connectAsync(register);
        chatReady.await();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        String text;
        while ((text = console.readLine()) != null) {
            if (!text.isBlank()) client.sendMessage(text);
        }
        client.disconnect();
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 250); return true;
        } catch (IOException e) { return false; }
    }

    private static Path tlsDir() throws IOException {
        Path dir = Paths.get(System.getProperty("user.home")).resolve(".messenger").resolve("tls");
        Files.createDirectories(dir);
        return dir;
    }

    private static Path setupLocalTls() throws Exception {
        Path dir        = tlsDir();
        Path keyStore   = dir.resolve("server-keystore.p12");
        Path cert       = dir.resolve("server-cert.pem");
        Path trustStore = dir.resolve("client-truststore.p12");

        if (!Files.isRegularFile(keyStore) || !Files.isRegularFile(trustStore)) {
            Files.deleteIfExists(keyStore);
            Files.deleteIfExists(cert);
            Files.deleteIfExists(trustStore);
            Path keytool = findKeytool();
            runKeytool(keytool, List.of("-genkeypair", "-alias", "messenger-server",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                    "-storepass", "changeit", "-keypass", "changeit",
                    "-validity", "365", "-dname", "CN=localhost",
                    "-ext", "SAN=DNS:localhost,IP:127.0.0.1"));
            runKeytool(keytool, List.of("-exportcert", "-alias", "messenger-server",
                    "-keystore", keyStore.toString(), "-storepass", "changeit",
                    "-rfc", "-file", cert.toString()));
            runKeytool(keytool, List.of("-importcert", "-alias", "messenger-server",
                    "-file", cert.toString(), "-keystore", trustStore.toString(),
                    "-storetype", "PKCS12", "-storepass", "changeit", "-noprompt"));
        }

        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        return keyStore;
    }

    private static void startLocalTlsServer(Path keyStore, int port) throws Exception {
        if (serverProcess != null && serverProcess.isAlive()) return;
        Path cwd     = Paths.get("").toAbsolutePath().normalize();
        Path logFile = tlsDir().resolve("tls-server.log");
        String java  = Paths.get(System.getProperty("java.home")).resolve("bin")
                .resolve(System.getProperty("os.name","").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        String cp    = cwd + File.pathSeparator + cwd.resolve("target").resolve("classes")
                     + File.pathSeparator + System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(java,
                "-Dserver.tls=true", "-Dserver.port=" + port,
                "-Djavax.net.ssl.keyStore=" + keyStore,
                "-Djavax.net.ssl.keyStorePassword=changeit",
                "-cp", cp, "Server");
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        serverProcess = pb.start();

        long deadline = System.currentTimeMillis() + 15_000;
        while (!isPortOpen("localhost", port)) {
            if (!serverProcess.isAlive()) {
                String out = Files.isRegularFile(logFile)
                        ? Files.readString(logFile, StandardCharsets.UTF_8).trim() : "";
                throw new IOException(out.isBlank() ? "TLS server exited during startup" : out);
            }
            if (System.currentTimeMillis() > deadline)
                throw new IOException("TLS server did not start within 15 seconds");
            Thread.sleep(200);
        }
    }

    private static void configureLocalTrustStore() throws IOException {
        if (System.getProperty("javax.net.ssl.trustStore") != null) return;
        Path trustStore = tlsDir().resolve("client-truststore.p12");
        if (!Files.isRegularFile(trustStore))
            throw new IOException("TLS certificates not found. Reconnect to generate them automatically.");
        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    }

    private static Path findKeytool() throws IOException {
        String exe = System.getProperty("os.name","").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path kt = javaHome.resolve("bin").resolve(exe);
        if (Files.isRegularFile(kt)) return kt;
        Path parent = javaHome.getParent();
        if (parent != null) { kt = parent.resolve("bin").resolve(exe); if (Files.isRegularFile(kt)) return kt; }
        throw new IOException("Could not find keytool");
    }

    private static void runKeytool(Path keytool, List<String> args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().add(keytool.toString());
        pb.command().addAll(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(out.isBlank() ? "keytool failed" : out.trim());
    }
}
