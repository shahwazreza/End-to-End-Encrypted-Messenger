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

            MessengerClient client = new MessengerClient(username, peer, host, port);
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

        // Allow pressing Enter in the last field to trigger connect
        portField.setOnAction(e -> connectBtn.fire());

        root.getChildren().addAll(icon, title, subtitle,
                usernameField, peerField, hostField, portField,
                connectBtn, statusLabel);

        primaryStage.setScene(new Scene(root, 420, 510));
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

    private void status(Label label, String text, String color) {
        label.setText(text);
        label.setTextFill(Color.web(color));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
