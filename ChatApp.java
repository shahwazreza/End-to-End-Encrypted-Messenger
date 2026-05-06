import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
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

    private static final String BG      = "#1e1e2e";
    private static final String HEADER  = "#181825";
    private static final String SURFACE = "#313244";
    private static final String BLUE    = "#89b4fa";
    private static final String TEXT    = "#cdd6f4";
    private static final String SUBTEXT = "#a6adc8";
    private static final String GREEN   = "#a6e3a1";
    private static final String RED     = "#f38ba8";
    private static final String MUTED   = "#6c7086";

    private Stage   primaryStage;
    private Process localServerProcess;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("E2E Encrypted Messenger");
        stage.setResizable(false);
        showAuth();
        stage.show();
    }

    // ─── Auth screen ──────────────────────────────────────────────────────────

    private void showAuth() {
        VBox root = new VBox(12);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(36, 50, 36, 50));
        root.setStyle("-fx-background-color: " + BG + ";");

        Label icon = new Label("🔒");
        icon.setFont(Font.font(38));
        VBox.setMargin(icon, new Insets(0, 0, 2, 0));

        Label title = new Label("E2E Encrypted Messenger");
        title.setFont(Font.font("System", FontWeight.BOLD, 20));
        title.setTextFill(Color.web(TEXT));

        Label subtitle = new Label("Messages are end-to-end encrypted");
        subtitle.setFont(Font.font(12));
        subtitle.setTextFill(Color.web(SUBTEXT));
        VBox.setMargin(subtitle, new Insets(0, 0, 6, 0));

        // Sign In / Register toggle
        boolean[] isRegister = {false};
        Button loginTab    = toggleTab("Sign In",  true);
        Button registerTab = toggleTab("Register", false);
        loginTab.setOnAction(e -> {
            isRegister[0] = false;
            loginTab.setStyle(activeTabStyle());
            registerTab.setStyle(inactiveTabStyle());
        });
        registerTab.setOnAction(e -> {
            isRegister[0] = true;
            loginTab.setStyle(inactiveTabStyle());
            registerTab.setStyle(activeTabStyle());
        });
        HBox modeRow = new HBox(0, loginTab, registerTab);
        modeRow.setAlignment(Pos.CENTER);
        VBox.setMargin(modeRow, new Insets(0, 0, 4, 0));

        TextField     usernameField = inputField("Username");
        PasswordField passwordField = passwordField("Password");

        TextField hostField = inputField("Server host  (default: localhost)");
        TextField portField = inputField("Port  (default: 5000)");

        CheckBox tlsCheck = new CheckBox("Use TLS");
        tlsCheck.setSelected(Boolean.parseBoolean(System.getProperty("client.tls", "false")));
        tlsCheck.setTextFill(Color.web(SUBTEXT));
        tlsCheck.setFont(Font.font(12));
        tlsCheck.setMaxWidth(Double.MAX_VALUE);

        Button tlsSetupBtn = new Button("Start local TLS server");
        tlsSetupBtn.setMaxWidth(Double.MAX_VALUE);
        tlsSetupBtn.setFont(Font.font("System", FontWeight.BOLD, 12));
        tlsSetupBtn.setStyle(secondaryBtnStyle());

        Button connectBtn = new Button("Sign In");
        connectBtn.setMaxWidth(Double.MAX_VALUE);
        connectBtn.setFont(Font.font("System", FontWeight.BOLD, 14));
        connectBtn.setStyle(primaryBtnStyle());
        VBox.setMargin(connectBtn, new Insets(4, 0, 0, 0));

        // Update button label when mode changes
        loginTab.setOnAction(e -> {
            isRegister[0] = false;
            loginTab.setStyle(activeTabStyle());
            registerTab.setStyle(inactiveTabStyle());
            connectBtn.setText("Sign In");
        });
        registerTab.setOnAction(e -> {
            isRegister[0] = true;
            loginTab.setStyle(inactiveTabStyle());
            registerTab.setStyle(activeTabStyle());
            connectBtn.setText("Create Account");
        });

        Label statusLabel = new Label(" ");
        statusLabel.setFont(Font.font(12));
        statusLabel.setTextFill(Color.web(SUBTEXT));
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(320);
        statusLabel.setAlignment(Pos.CENTER);

        connectBtn.setOnAction(e -> {
            String username = usernameField.getText().trim().toLowerCase();
            String password = passwordField.getText();
            String host     = hostField.getText().trim().isEmpty() ? "localhost" : hostField.getText().trim();
            int    port;
            try {
                port = portField.getText().trim().isEmpty() ? 5000 : Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                status(statusLabel, "Invalid port number", RED); return;
            }
            if (username.isEmpty() || password.isEmpty()) {
                status(statusLabel, "Username and password are required", RED); return;
            }

            connectBtn.setDisable(true);
            status(statusLabel, "Connecting...", SUBTEXT);

            if (tlsCheck.isSelected()) {
                try { configureLocalTrustStore(); }
                catch (IOException ex) {
                    status(statusLabel, ex.getMessage(), RED);
                    connectBtn.setDisable(false); return;
                }
            }

            MessengerClient client = new MessengerClient(username, password, host, port, tlsCheck.isSelected());
            client.setOnStatus(msg -> Platform.runLater(() -> status(statusLabel, msg, SUBTEXT)));
            client.setOnAuthSuccess(() -> Platform.runLater(() -> showDashboard(client)));
            client.setOnError(err -> Platform.runLater(() -> {
                status(statusLabel, err, RED);
                connectBtn.setDisable(false);
            }));
            client.connectAsync(isRegister[0]);
        });

        tlsSetupBtn.setOnAction(e -> {
            tlsSetupBtn.setDisable(true);
            status(statusLabel, "Setting up TLS...", SUBTEXT);
            int serverPort;
            try {
                serverPort = portField.getText().trim().isEmpty() ? 5000 : Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                status(statusLabel, "Invalid port", RED);
                tlsSetupBtn.setDisable(false); return;
            }
            new Thread(() -> {
                try {
                    if (isPortOpen("localhost", serverPort))
                        throw new IOException("Port " + serverPort + " already in use. Stop the old server first.");
                    TlsConfig cfg = setupLocalTls();
                    startLocalTlsServer(cfg, serverPort);
                    Platform.runLater(() -> {
                        tlsCheck.setSelected(true);
                        status(statusLabel, "TLS server running on port " + serverPort, GREEN);
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

        portField.setOnAction(ev -> connectBtn.fire());

        root.getChildren().addAll(icon, title, subtitle, modeRow,
                usernameField, passwordField,
                hostField, portField, tlsCheck, tlsSetupBtn,
                connectBtn, statusLabel);

        primaryStage.setScene(new Scene(root, 420, 620));
        primaryStage.setResizable(false);
    }

    // ─── Dashboard screen ─────────────────────────────────────────────────────

    private void showDashboard(MessengerClient client) {
        // Header
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 16, 14, 16));
        header.setStyle("-fx-background-color: " + HEADER + ";");

        Label lockIcon = new Label("🔒");
        lockIcon.setFont(Font.font(14));

        Label welcomeLabel = new Label("Welcome, " + client.getUsername());
        welcomeLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        welcomeLabel.setTextFill(Color.web(TEXT));

        Label encBadge = new Label("End-to-end encrypted");
        encBadge.setFont(Font.font(11));
        encBadge.setTextFill(Color.web(GREEN));

        VBox headerText = new VBox(2, welcomeLabel, encBadge);
        headerText.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button signOutBtn = new Button("Sign Out");
        signOutBtn.setStyle(secondaryBtnStyle() + "-fx-padding: 6 12;");
        signOutBtn.setFont(Font.font(12));

        header.getChildren().addAll(lockIcon, headerText, spacer, signOutBtn);

        // Search bar
        TextField searchField = new TextField();
        searchField.setPromptText("Search users...");
        searchField.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
                "-fx-prompt-text-fill: " + MUTED + "; -fx-background-radius: 20; -fx-padding: 9 14;");
        HBox searchRow = new HBox(searchField);
        searchRow.setPadding(new Insets(12, 16, 4, 16));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Label onlineHeader = new Label("ONLINE");
        onlineHeader.setFont(Font.font("System", FontWeight.BOLD, 11));
        onlineHeader.setTextFill(Color.web(MUTED));
        onlineHeader.setPadding(new Insets(6, 16, 4, 16));

        // User list
        ObservableList<String> allUsers      = FXCollections.observableArrayList();
        FilteredList<String>   filteredUsers = new FilteredList<>(allUsers, s -> true);
        ListView<String>       userList      = new ListView<>(filteredUsers);
        userList.setStyle("-fx-background-color: " + BG + "; -fx-border-color: transparent;");
        VBox.setVgrow(userList, Priority.ALWAYS);

        userList.setCellFactory(lv -> new ListCell<>() {
            private final Label  nameLabel = new Label();
            private final Button chatBtn   = new Button("Chat");
            private final Region cellSpacer = new Region();
            private final HBox   row       = new HBox(10, nameLabel, cellSpacer, chatBtn);

            {
                HBox.setHgrow(cellSpacer, Priority.ALWAYS);
                nameLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
                nameLabel.setTextFill(Color.web(TEXT));
                chatBtn.setStyle(primaryBtnStyle() + "-fx-padding: 5 14; -fx-background-radius: 12;");
                chatBtn.setFont(Font.font(12));
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8, 16, 8, 16));
                setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                chatBtn.setOnAction(e -> {
                    String peer = getItem();
                    if (peer != null) {
                        showChatScreen(client, peer);
                        client.startChatWith(peer);
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); }
                else { nameLabel.setText(item); setGraphic(row); }
            }
        });

        Label statusLabel = new Label("Loading online users...");
        statusLabel.setFont(Font.font(12));
        statusLabel.setTextFill(Color.web(SUBTEXT));
        statusLabel.setPadding(new Insets(8, 16, 8, 16));

        // Wire up live updates
        client.setOnUsersUpdated(users -> Platform.runLater(() -> {
            allUsers.setAll(users);
            updateOnlineStatus(statusLabel, users.size());
        }));
        client.setOnUserJoined(user -> Platform.runLater(() -> {
            if (!allUsers.contains(user)) allUsers.add(user);
            updateOnlineStatus(statusLabel, allUsers.size());
        }));
        client.setOnUserLeft(user -> Platform.runLater(() -> {
            allUsers.remove(user);
            updateOnlineStatus(statusLabel, allUsers.size());
        }));
        client.setOnDisconnected(() -> Platform.runLater(() ->
                status(statusLabel, "Disconnected from server", RED)));
        client.setOnError(err -> Platform.runLater(() -> status(statusLabel, err, RED)));
        client.setOnStatus(msg -> Platform.runLater(() -> status(statusLabel, msg, SUBTEXT)));

        // Callbacks are now set — request a fresh user list in case
        // the initial USERS message arrived before the dashboard was ready
        client.refreshUsers();

        searchField.textProperty().addListener((obs, old, val) -> {
            String filter = val.toLowerCase().trim();
            filteredUsers.setPredicate(u -> filter.isEmpty() || u.toLowerCase().contains(filter));
        });

        signOutBtn.setOnAction(e -> {
            client.disconnect();
            showAuth();
        });

        VBox body = new VBox(searchRow, onlineHeader, userList, statusLabel);
        VBox.setVgrow(userList, Priority.ALWAYS);
        body.setStyle("-fx-background-color: " + BG + ";");

        VBox root = new VBox(header, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.setStyle("-fx-background-color: " + BG + ";");

        primaryStage.setResizable(true);
        primaryStage.setMinWidth(380);
        primaryStage.setMinHeight(420);
        primaryStage.setScene(new Scene(root, 420, 560));
        primaryStage.setOnCloseRequest(ev -> client.disconnect());
    }

    private void updateOnlineStatus(Label label, int count) {
        String text  = count == 0 ? "No other users online" : count + " user" + (count == 1 ? "" : "s") + " online";
        String color = count == 0 ? MUTED : GREEN;
        label.setText(text);
        label.setTextFill(Color.web(color));
    }

    // ─── Chat screen ──────────────────────────────────────────────────────────

    private void showChatScreen(MessengerClient client, String peer) {
        // Header
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 16, 12, 16));
        header.setStyle("-fx-background-color: " + HEADER + ";");

        Button backBtn = new Button("← Back");
        backBtn.setStyle(secondaryBtnStyle() + "-fx-padding: 6 12;");
        backBtn.setFont(Font.font(12));

        Label peerLabel = new Label(peer);
        peerLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        peerLabel.setTextFill(Color.web(TEXT));

        Label connectionLabel = new Label("Establishing secure channel...");
        connectionLabel.setFont(Font.font(11));
        connectionLabel.setTextFill(Color.web(SUBTEXT));

        VBox peerBlock = new VBox(2, peerLabel, connectionLabel);
        peerBlock.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label encBadge = new Label("End-to-end encrypted");
        encBadge.setFont(Font.font(11));
        encBadge.setTextFill(Color.web(GREEN));

        header.getChildren().addAll(backBtn, peerBlock, spacer, encBadge);

        // Messages
        VBox messagesBox = new VBox(6);
        messagesBox.setPadding(new Insets(12));

        ScrollPane scroll = new ScrollPane(messagesBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: " + BG + "; -fx-background-color: " + BG + "; -fx-border-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        messagesBox.heightProperty().addListener((obs, o, n) -> scroll.setVvalue(1.0));

        // Load history
        List<MessageHistory.Entry> history = MessageHistory.load(client.getUsername(), peer);
        if (!history.isEmpty()) {
            messagesBox.getChildren().add(systemMessage("── Previous messages ──"));
            for (MessageHistory.Entry entry : history) {
                Label timeLabel = new Label(entry.formattedTime());
                timeLabel.setFont(Font.font(10));
                timeLabel.setTextFill(Color.web(MUTED));
                timeLabel.setMaxWidth(Double.MAX_VALUE);
                timeLabel.setAlignment(entry.sent() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                messagesBox.getChildren().add(new VBox(2, timeLabel, bubble(entry.text(), entry.sent())));
            }
            messagesBox.getChildren().add(systemMessage("── Now ──"));
        }
        messagesBox.getChildren().add(systemMessage("Secure channel established. Messages are end-to-end encrypted."));

        // Input bar
        TextField inputField = new TextField();
        inputField.setPromptText("Message " + peer + "...");
        inputField.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
                "-fx-prompt-text-fill: " + MUTED + "; -fx-background-radius: 20; -fx-padding: 10 14;");
        inputField.setDisable(true);
        HBox.setHgrow(inputField, Priority.ALWAYS);

        Button sendBtn = new Button("Send");
        sendBtn.setFont(Font.font("System", FontWeight.BOLD, 13));
        sendBtn.setStyle(primaryBtnStyle() + "-fx-background-radius: 20; -fx-padding: 10 18;");
        sendBtn.setDisable(true);

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

        sendBtn.setOnAction(ev -> send.run());
        inputField.setOnAction(ev -> send.run());

        client.setOnChatReady(() -> Platform.runLater(() -> {
            connectionLabel.setText("Secure channel active");
            connectionLabel.setTextFill(Color.web(GREEN));
            inputField.setDisable(false);
            sendBtn.setDisable(false);
            inputField.requestFocus();
        }));

        client.setOnMessageReceived(msg ->
                Platform.runLater(() -> messagesBox.getChildren().add(bubble(msg, false))));

        client.setOnStatus(msg -> Platform.runLater(() -> {
            boolean problem = msg.startsWith("Could not") || msg.startsWith("Malformed")
                    || msg.startsWith("Disconnected") || msg.startsWith("Timed out");
            connectionLabel.setText(msg);
            connectionLabel.setTextFill(Color.web(problem ? RED : SUBTEXT));
            if (problem) messagesBox.getChildren().add(systemMessage(msg, RED));
        }));

        client.setOnError(err -> Platform.runLater(() -> {
            connectionLabel.setText("Error");
            connectionLabel.setTextFill(Color.web(RED));
            inputField.setDisable(true);
            sendBtn.setDisable(true);
            messagesBox.getChildren().add(systemMessage(err, RED));
        }));

        client.setOnDisconnected(() -> Platform.runLater(() -> {
            connectionLabel.setText("Disconnected");
            connectionLabel.setTextFill(Color.web(RED));
            inputField.setDisable(true);
            sendBtn.setDisable(true);
        }));

        backBtn.setOnAction(ev -> {
            client.leaveChat();
            showDashboard(client);
        });

        VBox root = new VBox(header, scroll, inputRow);
        root.setStyle("-fx-background-color: " + BG + ";");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        primaryStage.setScene(new Scene(root, 520, 640));
        primaryStage.setOnCloseRequest(ev -> client.disconnect());
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

    private Label systemMessage(String text)               { return systemMessage(text, MUTED); }
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
        tf.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
                "-fx-prompt-text-fill: " + MUTED + "; -fx-background-radius: 8; -fx-padding: 10 12;");
        tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    private PasswordField passwordField(String prompt) {
        PasswordField pf = new PasswordField();
        pf.setPromptText(prompt);
        pf.setStyle("-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
                "-fx-prompt-text-fill: " + MUTED + "; -fx-background-radius: 8; -fx-padding: 10 12;");
        pf.setMaxWidth(Double.MAX_VALUE);
        return pf;
    }

    private Button toggleTab(String text, boolean active) {
        Button btn = new Button(text);
        btn.setFont(Font.font("System", FontWeight.BOLD, 12));
        btn.setStyle(active ? activeTabStyle() : inactiveTabStyle());
        btn.setMinWidth(120);
        return btn;
    }

    private String activeTabStyle() {
        return "-fx-background-color: " + BLUE + "; -fx-text-fill: #1e1e2e;" +
               "-fx-background-radius: 0; -fx-padding: 8 24; -fx-cursor: hand;";
    }

    private String inactiveTabStyle() {
        return "-fx-background-color: " + SURFACE + "; -fx-text-fill: " + SUBTEXT + ";" +
               "-fx-background-radius: 0; -fx-padding: 8 24; -fx-cursor: hand;";
    }

    private String primaryBtnStyle() {
        return "-fx-background-color: " + BLUE + "; -fx-text-fill: #1e1e2e;" +
               "-fx-background-radius: 8; -fx-padding: 10 20; -fx-cursor: hand;";
    }

    private String secondaryBtnStyle() {
        return "-fx-background-color: " + SURFACE + "; -fx-text-fill: " + TEXT + ";" +
               "-fx-background-radius: 8; -fx-padding: 10 20; -fx-cursor: hand;";
    }

    private void status(Label label, String text, String color) {
        label.setText(text);
        label.setTextFill(Color.web(color));
    }

    // ── TLS (unchanged from v1) ───────────────────────────────────────────────

    private TlsConfig setupLocalTls() throws Exception {
        Path cwd       = Paths.get("").toAbsolutePath().normalize();
        Path keyStore  = cwd.resolve("server-keystore.p12");
        Path cert      = cwd.resolve("server-cert.pem");
        Path trustStore = cwd.resolve("client-truststore.p12");
        Path keytool   = findKeytool();

        Files.deleteIfExists(keyStore);
        Files.deleteIfExists(cert);
        Files.deleteIfExists(trustStore);

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

        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        return new TlsConfig(cwd, keyStore, trustStore);
    }

    private void startLocalTlsServer(TlsConfig tlsConfig, int port) throws Exception {
        if (localServerProcess != null && localServerProcess.isAlive()) return;
        Path logFile = tlsConfig.cwd.resolve("tls-server.log");
        ProcessBuilder builder = new ProcessBuilder(
                findJava().toString(),
                "-Dserver.tls=true", "-Dserver.port=" + port,
                "-Djavax.net.ssl.keyStore=" + tlsConfig.keyStore,
                "-Djavax.net.ssl.keyStorePassword=changeit",
                "-cp", serverClassPath(tlsConfig.cwd), "Server");
        builder.directory(tlsConfig.cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        localServerProcess = builder.start();
        Thread.sleep(900);
        if (!localServerProcess.isAlive()) {
            String output = Files.isRegularFile(logFile)
                    ? Files.readString(logFile, StandardCharsets.UTF_8).trim() : "";
            throw new IOException(output.isBlank() ? "TLS server exited during startup" : output);
        }
    }

    private void configureLocalTrustStore() throws IOException {
        if (System.getProperty("javax.net.ssl.trustStore") != null) return;
        Path trustStore = Paths.get("").toAbsolutePath().normalize().resolve("client-truststore.p12");
        if (!Files.isRegularFile(trustStore))
            throw new IOException("Click 'Start local TLS server' before connecting with TLS");
        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 250); return true;
        } catch (IOException ignored) { return false; }
    }

    private Path findJava() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home")).resolve("bin").resolve(exe);
    }

    private String serverClassPath(Path cwd) {
        return cwd + File.pathSeparator + cwd.resolve("target").resolve("classes")
                + File.pathSeparator + System.getProperty("java.class.path");
    }

    private Path findKeytool() throws IOException {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path javaHome = Paths.get(System.getProperty("java.home"));
        Path keytool  = javaHome.resolve("bin").resolve(exe);
        if (Files.isRegularFile(keytool)) return keytool;
        Path parent = javaHome.getParent();
        if (parent != null) { keytool = parent.resolve("bin").resolve(exe); if (Files.isRegularFile(keytool)) return keytool; }
        throw new IOException("Could not find keytool");
    }

    private void runKeytool(Path keytool, List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command().add(keytool.toString());
        builder.command().addAll(args);
        builder.redirectErrorStream(true);
        Process p = builder.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(output.isBlank() ? "keytool failed" : output.trim());
    }

    private static class TlsConfig {
        final Path cwd, keyStore, trustStore;
        TlsConfig(Path cwd, Path keyStore, Path trustStore) {
            this.cwd = cwd; this.keyStore = keyStore; this.trustStore = trustStore;
        }
    }

    public static void main(String[] args) { launch(args); }
}
