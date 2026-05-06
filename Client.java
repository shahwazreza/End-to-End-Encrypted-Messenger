import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;

public class Client {

    private static final String HOST = System.getProperty("server.host", "localhost");
    private static final int PORT = Integer.parseInt(System.getProperty("server.port", "5000"));

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java Client <username> <peer>");
            System.exit(1);
        }

        String username = args[0].trim();
        String peer     = args[1].trim();

        if (username.isEmpty() || peer.isEmpty() || username.equals(peer)) {
            System.err.println("Username and peer must be non-empty and different");
            System.exit(1);
        }

        CountDownLatch ready = new CountDownLatch(1);
        MessengerClient client = new MessengerClient(username, peer, HOST, PORT);

        client.setOnConnected(() -> {
            System.out.println("Secure channel established with " + peer + ". Type messages below:");
            ready.countDown();
        });
        client.setOnMessageReceived(msg -> System.out.println("[" + peer + "]: " + msg));
        client.setOnStatus(status -> System.out.println("* " + status));
        client.setOnError(err -> {
            System.err.println("Error: " + err);
            System.exit(1);
        });
        client.setOnDisconnected(() -> {
            System.out.println("Disconnected.");
            System.exit(0);
        });

        client.connectAsync();
        ready.await();

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        String text;
        while ((text = console.readLine()) != null) {
            if (!text.isBlank()) client.sendMessage(text);
        }
        client.disconnect();
    }
}
