import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.*;
import javax.net.ssl.SSLServerSocketFactory;

public class Server {

    private static final int PORT = Integer.parseInt(System.getProperty("server.port", "5000"));
    private static final boolean USE_TLS = Boolean.parseBoolean(System.getProperty("server.tls", System.getProperty("tls", "false")));
    private static final Logger log = Logger.getLogger(Server.class.getName());

    private static final ConcurrentHashMap<String, Socket> clients = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PrintWriter> writers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> publicKeys = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = createServerSocket();
        log.info("Server listening on port " + PORT + (USE_TLS ? " with TLS" : ""));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { serverSocket.close(); } catch (IOException ignored) {}
            log.info("Server stopped");
        }));

        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client)).start();
            } catch (SocketException e) {
                if (!serverSocket.isClosed()) log.warning("Accept error: " + e.getMessage());
            }
        }
    }

    private static void handleClient(Socket socket) {
        String username = null;
        boolean registered = false;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            username = in.readLine();
            if (username == null || username.isBlank()) {
                socket.close();
                return;
            }
            username = username.trim();

            Socket existing = clients.putIfAbsent(username, socket);
            if (existing != null) {
                out.println("ERROR|Username '" + username + "' is already connected");
                log.warning("Rejected duplicate username: " + username);
                return;
            }
            writers.put(username, out);
            registered = true;
            out.println("OK|CONNECTED");
            log.info(username + " connected from " + socket.getInetAddress());

            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("REGISTER|")) {
                    String[] parts = line.split("\\|", 3);
                    if (parts.length == 3 && !parts[2].isBlank()) {
                        publicKeys.put(parts[1].trim(), parts[2].trim());
                        log.info(parts[1].trim() + " registered public key");
                    }
                } else if (line.startsWith("GETKEY|")) {
                    String[] parts = line.split("\\|", 2);
                    if (parts.length == 2) {
                        String peer = parts[1].trim();
                        String key = clients.containsKey(peer) ? publicKeys.get(peer) : null;
                        out.println("KEY|" + (key != null ? key : "null"));
                    }
                } else {
                    String[] parts = line.split("\\|", 2);
                    if (parts.length == 2) {
                        PrintWriter recipientOut = writers.get(parts[0].trim());
                        if (recipientOut != null) {
                            recipientOut.println("MSG|" + username + "|" + parts[1]);
                        } else {
                            out.println("ERROR|Message was not delivered because " + parts[0].trim() + " is not connected");
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warning("Client error (" + username + "): " + e.getMessage());
        } finally {
            if (registered && username != null) {
                clients.remove(username);
                writers.remove(username);
                publicKeys.remove(username);
                log.info(username + " disconnected");
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static ServerSocket createServerSocket() throws IOException {
        if (!USE_TLS) {
            return new ServerSocket(PORT);
        }
        return SSLServerSocketFactory.getDefault().createServerSocket(PORT);
    }
}
