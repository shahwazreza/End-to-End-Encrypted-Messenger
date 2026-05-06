import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class Server {

    private static final int PORT = Integer.parseInt(System.getProperty("server.port", "5000"));
    private static final Logger log = Logger.getLogger(Server.class.getName());

    private static final ConcurrentHashMap<String, Socket> clients = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PrintWriter> writers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> publicKeys = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        log.info("Server listening on port " + PORT);

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
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            username = in.readLine();
            if (username == null || username.isBlank()) {
                socket.close();
                return;
            }
            username = username.trim();

            clients.put(username, socket);
            writers.put(username, out);
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
            if (username != null) {
                clients.remove(username);
                writers.remove(username);
                publicKeys.remove(username);
                log.info(username + " disconnected");
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
