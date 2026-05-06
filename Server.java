import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.Collectors;
import javax.net.ssl.SSLServerSocketFactory;

public class Server {

    private static final int     PORT    = Integer.parseInt(System.getProperty("server.port", "5000"));
    private static final boolean USE_TLS = Boolean.parseBoolean(System.getProperty("server.tls", "false"));
    private static final Logger  log     = Logger.getLogger(Server.class.getName());

    private static final ConcurrentHashMap<String, PrintWriter> online     = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String>      publicKeys = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        ServerSocket server = createServerSocket();
        log.info("Server v2 listening on port " + PORT + (USE_TLS ? " [TLS]" : ""));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (IOException ignored) {}
        }));
        ExecutorService pool = Executors.newCachedThreadPool();
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                pool.submit(() -> handle(client));
            } catch (IOException e) {
                if (!server.isClosed()) log.warning("Accept error: " + e.getMessage());
            }
        }
    }

    private static void handle(Socket socket) {
        String username = null;
        try {
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    out = new PrintWriter(socket.getOutputStream(), true);

            out.println("OK|CONNECTED");

            // ── Auth ──────────────────────────────────────────────────────────
            String authLine = in.readLine();
            if (authLine == null) return;

            String[] a = authLine.split("\\|", 3);
            if (a.length != 3) { out.println("ERROR|Invalid auth format"); return; }

            String cmd  = a[0];
            String user = a[1].trim().toLowerCase();
            String pass = a[2];

            if (user.isEmpty() || pass.isEmpty()) {
                out.println("ERROR|Username and password are required"); return;
            }
            if (user.length() < 3) {
                out.println("ERROR|Username must be at least 3 characters"); return;
            }
            if (online.containsKey(user)) {
                out.println("ERROR|Already connected from another session"); return;
            }

            if ("REGISTER_ACCOUNT".equals(cmd)) {
                if (!AccountStore.register(user, pass)) {
                    out.println("ERROR|Username already taken"); return;
                }
            } else if ("LOGIN".equals(cmd)) {
                if (!AccountStore.authenticate(user, pass)) {
                    out.println("ERROR|Incorrect username or password"); return;
                }
            } else {
                out.println("ERROR|Unknown command"); return;
            }

            username = user;
            online.put(username, out);
            out.println("OK|AUTH");
            out.println("USERS|" + onlineList(username));
            broadcast("USER_JOINED|" + username, username);
            log.info(username + " joined  (online: " + online.size() + ")");

            // ── Message loop ──────────────────────────────────────────────────
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("PUBKEY|")) {
                    publicKeys.put(username, line.substring(7));

                } else if (line.startsWith("GETKEY|")) {
                    String peer = line.substring(7).trim().toLowerCase();
                    String key  = publicKeys.get(peer);
                    out.println("KEY|" + (key != null ? key : "null"));

                } else if (line.equals("USERS")) {
                    out.println("USERS|" + onlineList(username));

                } else {
                    int sep = line.indexOf('|');
                    if (sep > 0) {
                        String      target  = line.substring(0, sep).trim().toLowerCase();
                        String      payload = line.substring(sep + 1);
                        PrintWriter dest    = online.get(target);
                        if (dest != null) {
                            dest.println("MSG|" + username + "|" + payload);
                        } else {
                            out.println("ERROR|" + target + " is not online");
                        }
                    }
                }
            }

        } catch (Exception e) {
            if (username != null) log.fine(username + " error: " + e.getMessage());
        } finally {
            if (username != null) {
                online.remove(username);
                publicKeys.remove(username);
                broadcast("USER_LEFT|" + username, null);
                log.info(username + " left  (online: " + online.size() + ")");
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static String onlineList(String exclude) {
        return online.keySet().stream()
                .filter(u -> !u.equals(exclude))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static void broadcast(String message, String exclude) {
        online.forEach((user, writer) -> {
            if (exclude == null || !user.equals(exclude)) writer.println(message);
        });
    }

    private static ServerSocket createServerSocket() throws IOException {
        if (!USE_TLS) return new ServerSocket(PORT);
        return SSLServerSocketFactory.getDefault().createServerSocket(PORT);
    }
}
