import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ChatApp extends Application {

    // Catppuccin Mocha palette
    private static final String BG      = "#1e1e2e";
    private static final String HEADER  = "#181825";
    private static final String SURFACE = "#313244";
    private static final String BLUE    = "#89b4fa";
    private static final String TEXT    = "#cdd6f4";
    private static final String SUBTEXT = "#a6adc8";
    private static final String GREEN   = "#a6e3a1";
    private static final String RED     = "#f38ba8";
    private static final String MUTED   = "#6c7086";

    private Stage primaryStage;
    private Process localServerProcess;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("E2E Encrypted Messenger");
        stage.setResizable(false);
        showLogin();
        stage.show();
    }

    // ─── Login screen ────────────────────────────────────────────────────────

    private void showLogin() {
        VBox root = new VBox(14);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40, 50, 40, 50));
        root.setStyle("-fx-background-color: " + BG + ";");

        Label icon = new Label("🔒");
        icon.setFont(Font.font(38));
        VBox.setMargin(icon, new Insets(0, 0, 4, 0));

        Label title = new Label("E2E Encrypted Messenger");
        title.setFont(Font.font("System", FontWeight.BOLD, 20));
        title.setTextFill(Color.web(TEXT));

        Label subtitle = new Label("Messages are end-to-end encrypted");
        subtitle.setFont(Font.font(12));
        subtitle.setTextFill(Color.web(SUBTEXT));
        VBox.setMargin(subtitle, new Insets(0, 0, 8, 0));

        TextField usernameField = inputField("Your username");
        TextField peerField     = inputField("Peer username");
        TextField hostField     = inputField("Server host  (default: localhost)");
        TextField portField     = inputField("Port  (default: 5000)");
        CheckBox tlsCheck = new CheckBox("Use TLS");
        tlsCheck.setSelected(Boolean.parseBoolean(System.getProperty("client.tls", System.getProperty("tls", "false"))));
        tlsCheck.setTextFill(Color.web(SUBTEXT));
        tlsCheck.setFont(Font.font(12));
        tlsCheck.setMaxWidth(Double.MAX_VALUE);

        Button tlsSetupBtn = new Button("Start local TLS server");
        tlsSetupBtn.setMaxWidth(Double.MAX_VALUE);
        tlsSetupBtn.setFont(Font.font("System", FontWeight.BOLD, 12));
        tlsSetupBtn.setStyle(secondaryBtnStyle());

        Button connectBtn = new Button("Connect");
        connectBtn.setMaxWidth(Double.MAX_VALUE);
        connectBtn.setFont(Font.font("System", FontWeight.BOLD, 14));
        connectBtn.setStyle(primaryBtnStyle());
        VBox.setMargin(connectBtn, new Insets(6, 0, 0, 0));

        Label statusLabel = new Label(" ");
        statusLabel.setFont(Font.font(12));
        statusLabel.setTextFill(Color.web(SUBTEXT));

        connectBtn.setOnAction(e -> {
            String username = usernameField.getText().trim();
            String peer     = peerField.getText().trim();
            String host     = hostField.getText().trim().isEmpty() ? "localhost" : hostField.getText().trim();
            int port;
            try {
                port = portField.getText().trim().isEmpty() ? 5000 : Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                status(statusLabel, "Invalid port number", RED);
                return;
            }

            if (username.isEmpty() || peer.isEmpty()) {
                status(statusLabel, "Username and peer are required", RED);
                return;
            }
            if (username.equals(peer)) {
                status(statusLabel, "Username and peer must be different", RED);
                return;
            }

            connectBtn.setDisable(true);
            status(statusLabel, "Connecting to server...", SUBTEXT);

            if (tlsCheck.isSelected()) {
                try {
                    configureLocalTrustStore();
                } catch (IOException ex) {
                    status(statusLabel, ex.getMessage(), RED);
                    connectBtn.setDisable(false);
                    return;
                }
            }

            MessengerClient client = new MessengerClient(username, peer, host, port, tlsCheck.isSelected());
            client.setOnStatus(message ->
                Platform.runLater(() -> status(statusLabel, message, SUBTEXT)));

            client.setOnConnected(() ->
                Platform.runLater(() -> showChat(client, username, peer)));

            client.setOnError(err -> Platform.runLater(() -> {
                status(statusLabel, err, RED);
                connectBtn.setDisable(false);
            }));

            client.connectAsync();

        });

        tlsSetupBtn.setOnAction(e -> {
            tlsSetupBtn.setDisable(true);
            status(statusLabel, "Starting local TLS server...", SUBTEXT);
            int serverPort;
            try {
                serverPort = portField.getText().trim().isEmpty() ? 5000 : Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                status(statusLabel, "Invalid port number", RED);
                tlsSetupBtn.setDisable(false);
                return;
            }
            new Thread(() -> {
                try {
                    if (isPortOpen("localhost", serverPort)) {
                        throw new IOException("Port " + serverPort + " is already in use. Stop the old server first.");
                    }
                    TlsConfig tlsConfig = setupLocalTls();
                    startLocalTlsServer(tlsConfig, serverPort);
                    Platform.runLater(() -> {
                        tlsCheck.setSelected(true);
                        status(statusLabel, "Local TLS server running on port " + serverPort, GREEN);
                        tlsSetupBtn.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        status(statusLabel, "TLS setup failed: " + ex.getMessage(), RED);
                        tlsSetupBtn.setDisable(false);
                    });
                }
            }).start();
        });

        // Allow pressing Enter in the last field to trigger connect
        portField.setOnAction(e -> connectBtn.fire());

        root.getChildren().addAll(icon, title, subtitle,
                usernameField, peerField, hostField, portField,
                tlsCheck, tlsSetupBtn, connectBtn, statusLabel);

        primaryStage.setScene(new Scene(root, 420, 560));
    }

    // ─── Chat screen ─────────────────────────────────────────────────────────

    private void showChat(MessengerClient client, String username, String peer) {
        // Header bar
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 16, 14, 16));
        header.setStyle("-fx-background-color: " + HEADER + ";");

        Label lockIcon = new Label("🔒");
        lockIcon.setFont(Font.font(14));

        Label peerLabel = new Label(peer);
        peerLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        peerLabel.setTextFill(Color.web(TEXT));

        Label connectionLabel = new Label("Secure channel active");
        connectionLabel.setFont(Font.font(11));
        connectionLabel.setTextFill(Color.web(GREEN));

        VBox peerBlock = new VBox(2, peerLabel, connectionLabel);
        peerBlock.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label encBadge = new Label("End-to-end encrypted");
        encBadge.setFont(Font.font(11));
        encBadge.setTextFill(Color.web(GREEN));

        header.getChildren().addAll(lockIcon, peerBlock, spacer, encBadge);

        // Message list
        VBox messagesBox = new VBox(6);
        messagesBox.setPadding(new Insets(12));

        ScrollPane scroll = new ScrollPane(messagesBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: " + BG + "; -fx-background-color: " + BG + "; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        // Auto-scroll to newest message
        messagesBox.heightProperty().addListener((obs, o, n) -> scroll.setVvalue(1.0));

        messagesBox.getChildren().add(systemMessage("Secure channel established. Messages are end-to-end encrypted."));

        // Input bar
        TextField inputField = new TextField();
        inputField.setPromptText("Message " + peer + "...");
        inputField.setStyle(
                "-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
                "-fx-prompt-text-fill: " + MUTED + "; -fx-background-radius: 20; -fx-padding: 10 14;");
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Button sendBtn = new Button("Send");
        sendBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        sendBtn.setStyle(primaryBtnStyle() + "-fx-background-radius: 20; -fx-padding: 10 18;");

        HBox inputRow = new HBox(8, inputField, sendBtn);
        inputRow.setPadding(new Insets(10, 12, 10, 12));
        inputRow.setAlignment(Pos.CENTER);
        inputRow.setStyle("-fx-background-color: " + HEADER + ";");

        Runnable send = () -> {
            String text = inputField.getText().trim();
            if (text.isEmpty()) return;
            try {
                client.sendMessage(text);
                messagesBox.getChildren().add(bubble(text, true));
                inputField.clear();
            } catch (Exception ex) {
                messagesBox.getChildren().add(systemMessage("Send failed: " + ex.getMessage(), RED));
            }
        };

        sendBtn.setOnAction(e -> send.run());
        inputField.setOnAction(e -> send.run());

        client.setOnMessageReceived(msg ->
                Platform.runLater(() -> messagesBox.getChildren().add(bubble(msg, false))));

        client.setOnStatus(message -> Platform.runLater(() -> {
            boolean problem = message.startsWith("Could not")
                    || message.startsWith("Received malformed")
                    || message.startsWith("Message was not delivered")
                    || message.startsWith("Disconnected");
            connectionLabel.setText(message);
            connectionLabel.setTextFill(Color.web(problem ? RED : GREEN));
            if (problem) {
                messagesBox.getChildren().add(systemMessage(message, RED));
            }
        }));

        client.setOnError(err -> Platform.runLater(() -> {
            connectionLabel.setText("Connection error");
            connectionLabel.setTextFill(Color.web(RED));
            inputField.setDisable(true);
            sendBtn.setDisable(true);
            messagesBox.getChildren().add(systemMessage("Connection error: " + err, RED));
        }));

        client.setOnDisconnected(() -> Platform.runLater(() -> {
            connectionLabel.setText("Disconnected");
            connectionLabel.setTextFill(Color.web(RED));
            inputField.setDisable(true);
            sendBtn.setDisable(true);
            messagesBox.getChildren().add(systemMessage(peer + " disconnected.", RED));
        }));

        VBox root = new VBox(header, scroll, inputRow);
        root.setStyle("-fx-background-color: " + BG + ";");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        primaryStage.setResizable(true);
        primaryStage.setMinWidth(380);
        primaryStage.setMinHeight(420);
        primaryStage.setScene(new Scene(root, 520, 640));
        primaryStage.setOnCloseRequest(e -> client.disconnect());
        inputField.requestFocus();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HBox bubble(String text, boolean sent) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(310);
        label.setPadding(new Insets(8, 12, 8, 12));
        label.setFont(Font.font(13));
        label.setStyle(sent
                ? "-fx-background-color: " + BLUE + "; -fx-text-fill: #1e1e2e; -fx-background-radius: 16 16 4 16;"
                : "-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + "; -fx-background-radius: 16 16 16 4;");

        HBox row = new HBox(label);
        row.setAlignment(sent ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    private Label systemMessage(String text) {
        return systemMessage(text, MUTED);
    }

    private Label systemMessage(String text, String color) {
        Label label = new Label(text);
        label.setFont(Font.font(11));
        label.setTextFill(Color.web(color));
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        label.setPadding(new Insets(4, 0, 4, 0));
        return label;
    }

    private TextField inputField(String prompt) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setStyle(
                "-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
                "-fx-prompt-text-fill: " + MUTED + "; -fx-background-radius: 8; -fx-padding: 10 12;");
        tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    private String primaryBtnStyle() {
        return "-fx-background-color: " + BLUE + "; -fx-text-fill: #1e1e2e;" +
               "-fx-background-radius: 8; -fx-padding: 10 20; -fx-cursor: hand;";
    }

    private String secondaryBtnStyle() {
        return "-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
               "-fx-background-radius: 8; -fx-padding: 9 18; -fx-cursor: hand;";
    }

    private void status(Label label, String text, String color) {
        label.setText(text);
        label.setTextFill(Color.web(color));
    }

    private TlsConfig setupLocalTls() throws Exception {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path keyStore = cwd.resolve("server-keystore.p12");
        Path cert = cwd.resolve("server-cert.pem");
        Path trustStore = cwd.resolve("client-truststore.p12");
        Path keytool = findKeytool();

        Files.deleteIfExists(keyStore);
        Files.deleteIfExists(cert);
        Files.deleteIfExists(trustStore);

        runKeytool(keytool, List.of(
                "-genkeypair",
                "-alias", "messenger-server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-keystore", keyStore.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-validity", "365",
                "-dname", "CN=localhost",
                "-ext", "SAN=DNS:localhost,IP:127.0.0.1"
        ));
        runKeytool(keytool, List.of(
                "-exportcert",
                "-alias", "messenger-server",
                "-keystore", keyStore.toString(),
                "-storepass", "changeit",
                "-rfc",
                "-file", cert.toString()
        ));
        runKeytool(keytool, List.of(
                "-importcert",
                "-alias", "messenger-server",
                "-file", cert.toString(),
                "-keystore", trustStore.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-noprompt"
        ));

        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");

        return new TlsConfig(cwd, keyStore, trustStore);
    }

    private void startLocalTlsServer(TlsConfig tlsConfig, int port) throws Exception {
        if (localServerProcess != null && localServerProcess.isAlive()) {
            return;
        }

        Path logFile = tlsConfig.cwd.resolve("tls-server.log");
        ProcessBuilder builder = new ProcessBuilder(
                findJava().toString(),
                "-Dserver.tls=true",
                "-Dserver.port=" + port,
                "-Djavax.net.ssl.keyStore=" + tlsConfig.keyStore,
                "-Djavax.net.ssl.keyStorePassword=changeit",
                "-cp", serverClassPath(tlsConfig.cwd),
                "Server"
        );
        builder.directory(tlsConfig.cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        localServerProcess = builder.start();
        Thread.sleep(900);
        if (!localServerProcess.isAlive()) {
            String output = Files.isRegularFile(logFile)
                    ? Files.readString(logFile, StandardCharsets.UTF_8).trim()
                    : "";
            throw new IOException(output.isBlank() ? "Local TLS server exited during startup" : output);
        }
    }

    private void configureLocalTrustStore() throws IOException {
        if (System.getProperty("javax.net.ssl.trustStore") != null) {
            return;
        }

        Path trustStore = Paths.get("").toAbsolutePath().normalize().resolve("client-truststore.p12");
        if (!Files.isRegularFile(trustStore)) {
            throw new IOException("Click Set up local TLS before connecting with TLS");
        }

        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 250);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Path findJava() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Paths.get(System.getProperty("java.home")).resolve("bin").resolve(executable);
    }

    private String serverClassPath(Path cwd) {
        String currentClassPath = System.getProperty("java.class.path");
        return cwd
                + File.pathSeparator + cwd.resolve("target").resolve("classes")
                + File.pathSeparator + currentClassPath;
    }

    private Path findKeytool() throws IOException {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "keytool.exe"
                : "keytool";
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path keytool = javaHome.resolve("bin").resolve(executable);
        if (Files.isRegularFile(keytool)) {
            return keytool;
        }
        Path parent = javaHome.getParent();
        if (parent != null) {
            keytool = parent.resolve("bin").resolve(executable);
            if (Files.isRegularFile(keytool)) {
                return keytool;
            }
        }
        throw new IOException("Could not find keytool in the current JDK");
    }

    private void runKeytool(Path keytool, List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command().add(keytool.toString());
        builder.command().addAll(args);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(output.isBlank() ? "keytool exited with code " + exitCode : output.trim());
        }
    }

    private static class TlsConfig {
        private final Path cwd;
        private final Path keyStore;
        private final Path trustStore;

        private TlsConfig(Path cwd, Path keyStore, Path trustStore) {
            this.cwd = cwd;
            this.keyStore = keyStore;
            this.trustStore = trustStore;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
