import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Client {

    private static final String  HOST    = System.getProperty("server.host", "localhost");
    private static final int     PORT    = Integer.parseInt(System.getProperty("server.port", "5000"));
    private static final boolean USE_TLS = Boolean.parseBoolean(System.getProperty("client.tls", "false"));

    public static void main(String[] args) throws Exception {
        boolean register = args.length > 0 && "--register".equals(args[0]);
        String[] rest    = register ? java.util.Arrays.copyOfRange(args, 1, args.length) : args;

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
}
