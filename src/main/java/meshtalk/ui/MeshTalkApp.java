package meshtalk.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import meshtalk.client.TcpClient;
import meshtalk.common.Constants;
import meshtalk.common.Frame;
import meshtalk.nettools.FragmentationDemo;
import meshtalk.nettools.Ipv4Header;
import meshtalk.nettools.RoutingSimulator;
import meshtalk.nettools.SubnetCalculator;
import meshtalk.nettools.VlsmPlanner;
import meshtalk.rdt.*;
import meshtalk.server.ConcurrencyDemo;
import meshtalk.server.MeshTalkServer;
import meshtalk.server.ServerMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * JavaFX Dashboard for MeshTalk: A Multithreaded Client-Server Communication System.
 * Dark theme, live metrics, visual interactive tabs for all Computer Networks syllabus concepts.
 */
public class MeshTalkApp extends Application {

    private Stage primaryStage;
    private MeshTalkServer embeddedServer;
    private TcpClient embeddedClient;

    // Chat UI elements
    private TextArea chatArea;
    private TextField chatInput;
    private ListView<String> userListView;
    private ProgressBar fileProgressBar;
    private Label fileStatusLabel;

    // Inspector table
    private TableView<FrameLogEntry> inspectorTable;

    public static class FrameLogEntry {
        public final String timestamp;
        public final String direction;
        public final String type;
        public final int size;
        public final String payloadPreview;

        public FrameLogEntry(String direction, String type, int size, String payloadPreview) {
            this.timestamp = java.time.LocalTime.now().toString().substring(0, 8);
            this.direction = direction;
            this.type = type;
            this.size = size;
            this.payloadPreview = payloadPreview;
        }

        public String getTimestamp() { return timestamp; }
        public String getDirection() { return direction; }
        public String getType() { return type; }
        public int getSize() { return size; }
        public String getPayloadPreview() { return payloadPreview; }
    }

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        stage.setTitle("MeshTalk — Multithreaded Sockets & RDT Dashboard (JIIT Noida)");

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getTabs().add(createChatTab());
        tabPane.getTabs().add(createServerDashboardTab());
        tabPane.getTabs().add(createRdtLabTab());
        tabPane.getTabs().add(createProtocolInspectorTab());
        tabPane.getTabs().add(createNetToolsTab());
        tabPane.getTabs().add(createConcurrencyLabTab());
        tabPane.getTabs().add(createConceptMapTab());

        Scene scene = new Scene(tabPane, 1100, 720);
        applyDarkTheme(scene);

        stage.setScene(scene);
        stage.setOnCloseRequest(e -> cleanup());
        stage.show();
    }

    // ── TAB 1: CHAT & CLIENT ──────────────────────────────────────────

    private Tab createChatTab() {
        Tab tab = new Tab("💬 Live Chat");
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        // Top: Connection bar
        HBox connBar = new HBox(10);
        connBar.setAlignment(Pos.CENTER_LEFT);
        connBar.setPadding(new Insets(0, 0, 10, 0));

        TextField hostField = new TextField("localhost");
        hostField.setPrefWidth(120);
        TextField portField = new TextField("5000");
        portField.setPrefWidth(70);
        TextField userField = new TextField("User_" + (int)(Math.random() * 900 + 100));
        userField.setPrefWidth(120);
        Button connectBtn = new Button("Connect & Login");
        Button disconnectBtn = new Button("Disconnect");
        disconnectBtn.setDisable(true);

        connBar.getChildren().addAll(
                new Label("Host:"), hostField,
                new Label("Port:"), portField,
                new Label("User:"), userField,
                connectBtn, disconnectBtn
        );
        root.setTop(connBar);

        // Center: Chat area
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        root.setCenter(chatArea);

        // Right: Users & File send
        VBox rightPane = new VBox(10);
        rightPane.setPadding(new Insets(0, 0, 0, 10));
        rightPane.setPrefWidth(220);

        Label usersLabel = new Label("Online Users (TCP):");
        userListView = new ListView<>();
        userListView.setPrefHeight(250);

        Label fileLabel = new Label("TCP File Relay (SHA-256):");
        Button sendFileBtn = new Button("Select & Send File...");
        sendFileBtn.setMaxWidth(Double.MAX_VALUE);
        fileProgressBar = new ProgressBar(0);
        fileProgressBar.setMaxWidth(Double.MAX_VALUE);
        fileStatusLabel = new Label("Ready");

        rightPane.getChildren().addAll(usersLabel, userListView, new Separator(), fileLabel, sendFileBtn, fileProgressBar, fileStatusLabel);
        root.setRight(rightPane);

        // Bottom: Input bar
        HBox bottomBar = new HBox(10);
        bottomBar.setPadding(new Insets(10, 0, 0, 0));
        chatInput = new TextField();
        chatInput.setPromptText("Type message (/pm <user> <msg>, /help)...");
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        Button sendBtn = new Button("Send");
        bottomBar.getChildren().addAll(chatInput, sendBtn);
        root.setBottom(bottomBar);

        // Events
        connectBtn.setOnAction(e -> {
            try {
                String host = hostField.getText().trim();
                int port = Integer.parseInt(portField.getText().trim());
                String uName = userField.getText().trim();

                embeddedClient = new TcpClient(host, port);
                embeddedClient.setMessageListener(new TcpClient.MessageListener() {
                    @Override public void onMessage(String sender, String text) {
                        Platform.runLater(() -> appendChat(sender + ": " + text));
                        logInspector("RECV", "MSG_RELAY", text.length(), sender + ": " + text);
                    }
                    @Override public void onPrivateMessage(String sender, String text) {
                        Platform.runLater(() -> appendChat("[PM from " + sender + "]: " + text));
                        logInspector("RECV", "PRIVATE_RELAY", text.length(), text);
                    }
                    @Override public void onUserJoined(String user) {
                        Platform.runLater(() -> {
                            appendChat("*** " + user + " joined ***");
                            if (!userListView.getItems().contains(user)) userListView.getItems().add(user);
                        });
                        logInspector("RECV", "JOIN", user.length(), user);
                    }
                    @Override public void onUserLeft(String user) {
                        Platform.runLater(() -> {
                            appendChat("*** " + user + " left ***");
                            userListView.getItems().remove(user);
                        });
                        logInspector("RECV", "LEAVE", user.length(), user);
                    }
                    @Override public void onUserList(List<String> users) {
                        Platform.runLater(() -> {
                            userListView.getItems().setAll(users);
                        });
                    }
                    @Override public void onHistory(List<String> history) {
                        Platform.runLater(() -> {
                            for (String h : history) appendChat("[History] " + h);
                        });
                    }
                    @Override public void onFileOffer(String sender, String filename, long size) {
                        Platform.runLater(() -> {
                            appendChat("[File] Incoming '" + filename + "' (" + size + " bytes) from " + sender);
                            fileStatusLabel.setText("Receiving: " + filename);
                        });
                    }
                    @Override public void onFileProgress(String filename, double progress) {
                        Platform.runLater(() -> fileProgressBar.setProgress(progress));
                    }
                    @Override public void onFileComplete(String filename, boolean verified) {
                        Platform.runLater(() -> {
                            fileProgressBar.setProgress(1.0);
                            fileStatusLabel.setText(verified ? "VERIFIED (SHA-256 MATCH)" : "FAILED!");
                            appendChat("[File] " + filename + " -> " + (verified ? "VERIFIED" : "FAILED"));
                        });
                    }
                    @Override public void onRoomMessage(String room, String sender, String text) {
                        Platform.runLater(() -> appendChat("[" + room + "] " + sender + ": " + text));
                    }
                    @Override public void onError(String error) {
                        Platform.runLater(() -> appendChat("[ERROR] " + error));
                    }
                    @Override public void onDisconnect() {
                        Platform.runLater(() -> {
                            appendChat("[DISCONNECTED]");
                            connectBtn.setDisable(false);
                            disconnectBtn.setDisable(true);
                        });
                    }
                });

                if (embeddedClient.connectAndLogin(uName)) {
                    appendChat("Connected as " + uName);
                    connectBtn.setDisable(true);
                    disconnectBtn.setDisable(false);
                    embeddedClient.requestUserList();
                    logInspector("SEND", "LOGIN", uName.length(), uName);
                } else {
                    appendChat("Login failed!");
                }
            } catch (Exception ex) {
                appendChat("Connection error: " + ex.getMessage());
            }
        });

        disconnectBtn.setOnAction(e -> {
            if (embeddedClient != null) embeddedClient.disconnect();
            connectBtn.setDisable(false);
            disconnectBtn.setDisable(true);
        });

        Runnable sendAction = () -> {
            String text = chatInput.getText().trim();
            if (text.isEmpty() || embeddedClient == null || !embeddedClient.isConnected()) return;
            try {
                if (text.startsWith("/pm ")) {
                    String[] parts = text.split("\\s+", 3);
                    if (parts.length >= 3) {
                        embeddedClient.sendPrivateMessage(parts[1], parts[2]);
                        appendChat("[PM to " + parts[1] + "]: " + parts[2]);
                        logInspector("SEND", "PRIVATE", parts[2].length(), "to " + parts[1] + ": " + parts[2]);
                    }
                } else {
                    embeddedClient.sendMessage(text);
                    logInspector("SEND", "MSG", text.length(), text);
                }
                chatInput.clear();
            } catch (IOException ex) {
                appendChat("Send error: " + ex.getMessage());
            }
        };

        sendBtn.setOnAction(e -> sendAction.run());
        chatInput.setOnAction(e -> sendAction.run());

        sendFileBtn.setOnAction(e -> {
            String target = userListView.getSelectionModel().getSelectedItem();
            if (target == null) {
                appendChat("Select a target user from the list first!");
                return;
            }
            FileChooser fc = new FileChooser();
            File f = fc.showOpenDialog(primaryStage);
            if (f != null && embeddedClient != null) {
                new Thread(() -> {
                    try {
                        Platform.runLater(() -> fileStatusLabel.setText("Sending " + f.getName() + "..."));
                        embeddedClient.sendFile(target, f);
                        logInspector("SEND", "FILE_OFFER", (int) f.length(), f.getName() + " -> " + target);
                    } catch (Exception ex) {
                        Platform.runLater(() -> fileStatusLabel.setText("Error: " + ex.getMessage()));
                    }
                }).start();
            }
        });

        tab.setContent(root);
        return tab;
    }

    private void appendChat(String msg) {
        chatArea.appendText(msg + "\n");
    }

    // ── TAB 2: SERVER DASHBOARD ───────────────────────────────────────

    private Tab createServerDashboardTab() {
        Tab tab = new Tab("🖥️ Server Dashboard");
        VBox root = new VBox(15);
        root.setPadding(new Insets(15));

        // Controls: Mode switch & Start/Stop
        HBox topControls = new HBox(15);
        topControls.setAlignment(Pos.CENTER_LEFT);

        ComboBox<ServerMode> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll(ServerMode.values());
        modeCombo.setValue(ServerMode.THREAD_POOL);

        TextField poolSizeField = new TextField("16");
        poolSizeField.setPrefWidth(60);

        Button startServerBtn = new Button("Start Server");
        Button stopServerBtn = new Button("Stop Server");
        stopServerBtn.setDisable(true);

        Label serverStatusLabel = new Label("Status: Stopped");
        serverStatusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #ff5555;");

        topControls.getChildren().addAll(
                new Label("Architecture:"), modeCombo,
                new Label("Pool Size:"), poolSizeField,
                startServerBtn, stopServerBtn, serverStatusLabel
        );

        // Metrics Grid
        GridPane metricsGrid = new GridPane();
        metricsGrid.setHgap(20);
        metricsGrid.setVgap(10);

        Label clientsCount = new Label("0");
        Label threadsCount = new Label("0");
        Label queueCount = new Label("0");
        Label msgRateLabel = new Label("0.0 msg/s");
        Label totalBytesLabel = new Label("0 B");

        metricsGrid.add(new Label("Connected Clients:"), 0, 0);
        metricsGrid.add(clientsCount, 1, 0);
        metricsGrid.add(new Label("Active Threads:"), 2, 0);
        metricsGrid.add(threadsCount, 3, 0);
        metricsGrid.add(new Label("Queue Depth:"), 4, 0);
        metricsGrid.add(queueCount, 5, 0);
        metricsGrid.add(new Label("Throughput:"), 0, 1);
        metricsGrid.add(msgRateLabel, 1, 1);
        metricsGrid.add(new Label("Total Bytes:"), 2, 1);
        metricsGrid.add(totalBytesLabel, 3, 1);

        // Live Throughput Chart
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Elapsed Time (s)");
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Messages / sec");
        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Live Server Throughput");
        chart.setAnimated(false);
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("Throughput (msg/s)");
        chart.getData().add(series);

        // Periodic poller
        javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), ev -> {
                    if (embeddedServer != null && embeddedServer.isRunning()) {
                        clientsCount.setText(String.valueOf(embeddedServer.getMetrics().getConnectedClients()));
                        threadsCount.setText(String.valueOf(embeddedServer.getMetrics().getActiveThreads()));
                        queueCount.setText(String.valueOf(embeddedServer.getMetrics().getQueueLength()));
                        double mps = embeddedServer.getMetrics().getMessagesPerSecond();
                        msgRateLabel.setText(String.format("%.1f msg/s", mps));
                        totalBytesLabel.setText(String.format("%d bytes", embeddedServer.getMetrics().getTotalBytes()));

                        double time = embeddedServer.getMetrics().getUptime();
                        series.getData().add(new XYChart.Data<>(time, mps));
                        if (series.getData().size() > 30) series.getData().remove(0);
                    }
                })
        );
        timeline.setCycleCount(javafx.animation.Animation.INDEFINITE);

        startServerBtn.setOnAction(e -> {
            try {
                int pSize = Integer.parseInt(poolSizeField.getText().trim());
                embeddedServer = new MeshTalkServer(Constants.DEFAULT_TCP_PORT, modeCombo.getValue(), pSize);
                embeddedServer.start();
                serverStatusLabel.setText("Status: RUNNING (" + modeCombo.getValue() + ")");
                serverStatusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #55ff55;");
                startServerBtn.setDisable(true);
                stopServerBtn.setDisable(false);
                timeline.play();
            } catch (Exception ex) {
                serverStatusLabel.setText("Error: " + ex.getMessage());
            }
        });

        stopServerBtn.setOnAction(e -> {
            if (embeddedServer != null) embeddedServer.stop();
            serverStatusLabel.setText("Status: Stopped");
            serverStatusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #ff5555;");
            startServerBtn.setDisable(false);
            stopServerBtn.setDisable(true);
            timeline.stop();
        });

        root.getChildren().addAll(topControls, new Separator(), metricsGrid, new Separator(), chart);
        tab.setContent(root);
        return tab;
    }

    // ── TAB 3: RDT LAB (UDP RELIABLE TRANSFER) ─────────────────────────

    private Tab createRdtLabTab() {
        Tab tab = new Tab("⚡ RDT over UDP Lab");
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        // Param sliders & selectors
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(8);

        ComboBox<RdtFileTransfer.ProtocolType> protoCombo = new ComboBox<>();
        protoCombo.getItems().addAll(RdtFileTransfer.ProtocolType.values());
        protoCombo.setValue(RdtFileTransfer.ProtocolType.SELECTIVE_REPEAT);

        ComboBox<CongestionController.Mode> ccCombo = new ComboBox<>();
        ccCombo.getItems().addAll(CongestionController.Mode.values());
        ccCombo.setValue(CongestionController.Mode.RENO);

        Slider lossSlider = new Slider(0, 50, 10);
        lossSlider.setShowTickLabels(true);
        lossSlider.setShowTickMarks(true);
        Label lossLabel = new Label("10%");

        Slider delaySlider = new Slider(0, 100, 5);
        delaySlider.setShowTickLabels(true);
        Label delayLabel = new Label("5 ms");

        Slider winSlider = new Slider(1, 32, 8);
        winSlider.setShowTickLabels(true);
        Label winLabel = new Label("8 pkts");

        lossSlider.valueProperty().addListener((o, oldV, newV) -> lossLabel.setText(String.format("%d%%", newV.intValue())));
        delaySlider.valueProperty().addListener((o, oldV, newV) -> delayLabel.setText(String.format("%d ms", newV.intValue())));
        winSlider.valueProperty().addListener((o, oldV, newV) -> winLabel.setText(String.format("%d", newV.intValue())));

        grid.add(new Label("Protocol:"), 0, 0); grid.add(protoCombo, 1, 0);
        grid.add(new Label("Congestion Control:"), 2, 0); grid.add(ccCombo, 3, 0);
        grid.add(new Label("Packet Loss %:"), 0, 1); grid.add(lossSlider, 1, 1); grid.add(lossLabel, 2, 1);
        grid.add(new Label("Channel Delay:"), 0, 2); grid.add(delaySlider, 1, 2); grid.add(delayLabel, 2, 2);
        grid.add(new Label("Window Size (N):"), 0, 3); grid.add(winSlider, 1, 3); grid.add(winLabel, 2, 3);

        Button runRdtBtn = new Button("🚀 Run RDT Transfer Simulation");
        ProgressBar rdtProgress = new ProgressBar(0);
        rdtProgress.setPrefWidth(300);
        Label rdtSummary = new Label("Summary: Ready");
        rdtSummary.setStyle("-fx-font-weight: bold;");

        // Live CWND Chart
        NumberAxis cwndX = new NumberAxis(); cwndX.setLabel("Elapsed (ms)");
        NumberAxis cwndY = new NumberAxis(); cwndY.setLabel("CWND (MSS)");
        LineChart<Number, Number> cwndChart = new LineChart<>(cwndX, cwndY);
        cwndChart.setTitle("Sliding Window & Congestion Window (AIMD)");
        cwndChart.setAnimated(false);
        XYChart.Series<Number, Number> cwndSeries = new XYChart.Series<>();
        cwndSeries.setName("CWND");
        cwndChart.getData().add(cwndSeries);

        runRdtBtn.setOnAction(e -> {
            runRdtBtn.setDisable(true);
            cwndSeries.getData().clear();
            rdtSummary.setText("Simulating...");

            new Thread(() -> {
                try {
                    File tempIn = File.createTempFile("rdt_lab_in", ".dat");
                    byte[] data = new byte[32768]; // 32 KB payload
                    new java.util.Random().nextBytes(data);
                    Files.write(tempIn.toPath(), data);
                    File tempOut = File.createTempFile("rdt_lab_out", ".dat");

                    long simStart = System.currentTimeMillis();

                    RdtFileTransfer.TransferResult res = RdtFileTransfer.runLocalTransfer(
                            tempIn, tempOut,
                            protoCombo.getValue(),
                            (int) winSlider.getValue(),
                            lossSlider.getValue() / 100.0,
                            0.0,
                            (int) delaySlider.getValue(),
                            ccCombo.getValue(),
                            new RdtEventListener() {
                                @Override public void onPacketSent(int seqNo, int size) {
                                    logInspector("RDT-SEND", "DATA", size, "seq=" + seqNo);
                                }
                                @Override public void onAckReceived(int ackNo) {
                                    logInspector("RDT-RECV", "ACK", 17, "ack=" + ackNo);
                                }
                                @Override public void onTimeout(int seqNo) {
                                    logInspector("RDT-EVENT", "TIMEOUT", 0, "seq=" + seqNo);
                                }
                                @Override public void onRetransmit(int seqNo) {
                                    logInspector("RDT-RETRANS", "DATA", 1024, "seq=" + seqNo);
                                }
                                @Override public void onDupAck(int ackNo, int count) {}
                                @Override public void onCwndChange(double cwnd, double ssthresh) {
                                    Platform.runLater(() -> {
                                        long elapsed = System.currentTimeMillis() - simStart;
                                        cwndSeries.getData().add(new XYChart.Data<>(elapsed, cwnd));
                                    });
                                }
                                @Override public void onRttSample(long rtt, long estimatedRtt, long rto) {}
                                @Override public void onComplete(long totalTime, long totalBytes, int retransmissions, String sha256) {}
                            }
                    );

                    Platform.runLater(() -> {
                        runRdtBtn.setDisable(false);
                        rdtProgress.setProgress(1.0);
                        rdtSummary.setText(String.format("Done in %d ms | Throughput: %.1f KB/s | Retransmissions: %d | SHA-256: %s",
                                res.elapsedMs, res.throughputKBps, res.retransmissions, res.verified ? "VERIFIED (MATCH)" : "MISMATCH"));
                    });

                    tempIn.delete();
                    tempOut.delete();
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        runRdtBtn.setDisable(false);
                        rdtSummary.setText("Failed: " + ex.getMessage());
                    });
                }
            }).start();
        });

        root.getChildren().addAll(grid, new HBox(15, runRdtBtn, rdtProgress), rdtSummary, new Separator(), cwndChart);
        tab.setContent(root);
        return tab;
    }

    // ── TAB 4: PROTOCOL INSPECTOR (WIRESHARK-STYLE) ────────────────────

    private Tab createProtocolInspectorTab() {
        Tab tab = new Tab("🔍 Protocol Inspector");
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        inspectorTable = new TableView<>();

        TableColumn<FrameLogEntry, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getTimestamp()));
        timeCol.setPrefWidth(100);

        TableColumn<FrameLogEntry, String> dirCol = new TableColumn<>("Dir");
        dirCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getDirection()));
        dirCol.setPrefWidth(90);

        TableColumn<FrameLogEntry, String> typeCol = new TableColumn<>("Frame Type");
        typeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getType()));
        typeCol.setPrefWidth(120);

        TableColumn<FrameLogEntry, Number> sizeCol = new TableColumn<>("Length");
        sizeCol.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().getSize()));
        sizeCol.setPrefWidth(80);

        TableColumn<FrameLogEntry, String> infoCol = new TableColumn<>("Decoded Payload Info");
        infoCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getPayloadPreview()));
        infoCol.setPrefWidth(650);

        inspectorTable.getColumns().addAll(timeCol, dirCol, typeCol, sizeCol, infoCol);

        TextArea frameDetail = new TextArea("Select a frame to view decoded fields.");
        frameDetail.setPrefHeight(100);
        frameDetail.setEditable(false);

        inspectorTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                frameDetail.setText(String.format("FRAME DECODED DETAILS:\nTimestamp: %s\nDirection: %s\nProtocol Type: %s\nWire Length: %d bytes\nPayload: %s",
                        newV.getTimestamp(), newV.getDirection(), newV.getType(), newV.getSize(), newV.getPayloadPreview()));
            }
        });

        Button clearBtn = new Button("Clear Log");
        clearBtn.setOnAction(e -> inspectorTable.getItems().clear());

        root.getChildren().addAll(new HBox(clearBtn), inspectorTable, new Label("Header & Framing Breakdown:"), frameDetail);
        tab.setContent(root);
        return tab;
    }

    private void logInspector(String dir, String type, int size, String preview) {
        Platform.runLater(() -> {
            if (inspectorTable != null) {
                inspectorTable.getItems().add(new FrameLogEntry(dir, type, size, preview));
                if (inspectorTable.getItems().size() > 200) inspectorTable.getItems().remove(0);
            }
        });
    }

    // ── TAB 5: NETWORK TOOLS (SUBNET & ROUTING) ────────────────────────

    private Tab createNetToolsTab() {
        Tab tab = new Tab("🌐 Network Tools");
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        TabPane subTabs = new TabPane();
        subTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Subnet Tab
        Tab subCalcTab = new Tab("Subnet & CIDR");
        VBox subVBox = new VBox(10);
        subVBox.setPadding(new Insets(10));
        HBox subInput = new HBox(10);
        TextField ipCidrField = new TextField("192.168.1.130/26");
        Button calcBtn = new Button("Calculate");
        subInput.getChildren().addAll(new Label("IP / CIDR:"), ipCidrField, calcBtn);
        TextArea subOutput = new TextArea();
        subOutput.setEditable(false);
        calcBtn.setOnAction(e -> {
            try {
                SubnetCalculator.SubnetInfo info = SubnetCalculator.calculate(ipCidrField.getText().trim());
                subOutput.setText(info.toString());
            } catch (Exception ex) {
                subOutput.setText("Error: " + ex.getMessage());
            }
        });
        subVBox.getChildren().addAll(subInput, subOutput);
        subCalcTab.setContent(subVBox);

        // Fragmentation Tab
        Tab fragTab = new Tab("IPv4 & Fragmentation");
        VBox fragVBox = new VBox(10);
        fragVBox.setPadding(new Insets(10));
        HBox fragInputs = new HBox(10);
        TextField packetSizeField = new TextField("4000");
        packetSizeField.setPrefWidth(80);
        TextField mtuField = new TextField("1500");
        mtuField.setPrefWidth(80);
        Button runFragBtn = new Button("Fragment & Reassemble");
        fragInputs.getChildren().addAll(new Label("Payload Bytes:"), packetSizeField, new Label("Path MTU:"), mtuField, runFragBtn);
        TextArea fragOutput = new TextArea();
        fragOutput.setEditable(false);
        runFragBtn.setOnAction(e -> {
            try {
                int pSize = Integer.parseInt(packetSizeField.getText().trim());
                int mtu = Integer.parseInt(mtuField.getText().trim());
                Ipv4Header baseHdr = new Ipv4Header(0, 20 + pSize, 54321, 0, 0, 64, 6, "192.168.1.10", "10.0.0.1");
                byte[] rawPayload = new byte[pSize];
                List<FragmentationDemo.Fragment> frags = FragmentationDemo.fragment(baseHdr, rawPayload, mtu);
                byte[] reassembled = FragmentationDemo.reassemble(frags);

                StringBuilder sb = new StringBuilder();
                sb.append(baseHdr.toDetailedString()).append("\n\n");
                sb.append("--- GENERATED FRAGMENTS (MTU=").append(mtu).append(") ---\n");
                for (FragmentationDemo.Fragment f : frags) {
                    sb.append(f.toString()).append("\n");
                }
                sb.append(String.format("\nReassembly check: Original=%d bytes, Reassembled=%d bytes -> %s\n",
                        pSize, reassembled.length, (pSize == reassembled.length ? "SUCCESS" : "FAILED")));
                fragOutput.setText(sb.toString());
            } catch (Exception ex) {
                fragOutput.setText("Error: " + ex.getMessage());
            }
        });
        fragVBox.getChildren().addAll(fragInputs, fragOutput);
        fragTab.setContent(fragVBox);

        // Routing Tab
        Tab routeTab = new Tab("Dijkstra & Distance Vector");
        VBox rVBox = new VBox(10);
        rVBox.setPadding(new Insets(10));
        HBox rBtns = new HBox(10);
        Button dijkstraBtn = new Button("Run Dijkstra (Link State)");
        Button dvBtn = new Button("Run Bellman-Ford (Distance Vector)");
        Button ctiBtn = new Button("Simulate Count-To-Infinity");
        Button ctiFixBtn = new Button("Count-To-Infinity + Poison Reverse");
        rBtns.getChildren().addAll(dijkstraBtn, dvBtn, ctiBtn, ctiFixBtn);
        TextArea rOutput = new TextArea();
        rOutput.setEditable(false);

        dijkstraBtn.setOnAction(e -> {
            RoutingSimulator.Topology top = RoutingSimulator.Topology.createKuroseTopology();
            RoutingSimulator.DijkstraResult res = RoutingSimulator.dijkstra(top, "u");
            StringBuilder sb = new StringBuilder("=== DIJKSTRA'S ALGORITHM (SOURCE: u) ===\n\n");
            for (RoutingSimulator.DijkstraStep s : res.steps) {
                sb.append(String.format("Step %d: Added %s | Visited: %s | Distances: %s\n",
                        s.stepNumber, s.addedNode, s.visitedSet, s.tentativeDistances));
            }
            sb.append("\nFinal Shortest Path Routing Table:\n");
            for (RoutingSimulator.RouteEntry re : res.routingTable.values()) {
                sb.append("  ").append(re).append("\n");
            }
            rOutput.setText(sb.toString());
        });

        dvBtn.setOnAction(e -> {
            RoutingSimulator.Topology top = RoutingSimulator.Topology.createKuroseTopology();
            RoutingSimulator.DvResult res = RoutingSimulator.distanceVector(top);
            StringBuilder sb = new StringBuilder("=== DISTANCE VECTOR ALGORITHM (BELLMAN-FORD) ===\n\n");
            for (RoutingSimulator.DvIteration it : res.rounds) {
                sb.append("Round ").append(it.iteration).append(" Vectors: ").append(it.distanceVectors).append("\n");
            }
            sb.append("\nConverged Routing Tables:\n");
            for (String node : res.routingTables.keySet()) {
                sb.append("Router ").append(node).append(":\n");
                for (RoutingSimulator.RouteEntry re : res.routingTables.get(node).values()) {
                    sb.append("  ").append(re).append("\n");
                }
            }
            rOutput.setText(sb.toString());
        });

        ctiBtn.setOnAction(e -> rOutput.setText(RoutingSimulator.demonstrateCountToInfinity(false)));
        ctiFixBtn.setOnAction(e -> rOutput.setText(RoutingSimulator.demonstrateCountToInfinity(true)));

        rVBox.getChildren().addAll(rBtns, rOutput);
        routeTab.setContent(rVBox);

        subTabs.getTabs().addAll(subCalcTab, fragTab, routeTab);
        root.getChildren().add(subTabs);
        tab.setContent(root);
        return tab;
    }

    // ── TAB 6: CONCURRENCY LAB ─────────────────────────────────────────

    private Tab createConcurrencyLabTab() {
        Tab tab = new Tab("⚙️ Concurrency Lab");
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        HBox btnBox = new HBox(15);
        Button raceBtn = new Button("Run Race Condition Demo");
        Button deadlockBtn = new Button("Simulate Deadlock");
        Button fixDeadlockBtn = new Button("Deadlock Fixed (Lock Ordering)");
        btnBox.getChildren().addAll(raceBtn, deadlockBtn, fixDeadlockBtn);

        TextArea demoOutput = new TextArea();
        demoOutput.setEditable(false);

        raceBtn.setOnAction(e -> {
            try {
                demoOutput.setText(ConcurrencyDemo.demonstrateRaceCondition(8, 10000));
            } catch (Exception ex) {
                demoOutput.setText("Error: " + ex.getMessage());
            }
        });

        deadlockBtn.setOnAction(e -> demoOutput.setText(ConcurrencyDemo.demonstrateDeadlock(false)));
        fixDeadlockBtn.setOnAction(e -> demoOutput.setText(ConcurrencyDemo.demonstrateDeadlock(true)));

        root.getChildren().addAll(btnBox, demoOutput);
        tab.setContent(root);
        return tab;
    }

    // ── TAB 7: CONCEPT MAP ─────────────────────────────────────────────

    private Tab createConceptMapTab() {
        Tab tab = new Tab("📋 Syllabus Concept Map");
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setText("""
                ========================================================================================
                          MESHTALK: COMPUTER NETWORKS (24B11CS311) SYLLABUS CONCEPT MAPPING
                ========================================================================================

                1. APPLICATION LAYER
                   • Client-Server Architecture: ServerSocket accept loop + multithreaded client sessions.
                   • Framing & Stream Demarcation: [4-byte length][1-byte type][payload] with readFully().
                   • Application Protocols: LOGIN, MSG, PRIVATE, FILE_OFFER, ROOMS. Decoded live in Inspector.
                   • UDP Service: LAN auto-discovery with broadcast and RTT latency estimation.

                2. TRANSPORT LAYER
                   • TCP Socket Programming: Raw java.net.Socket & ServerSocket without external frameworks.
                   • Multithreading Models: SINGLE vs THREAD_PER_CLIENT vs THREAD_POOL (16 threads).
                   • Concurrency Issues: Race conditions (atomic CAS fix) and Deadlocks (lock hierarchy fix).
                   • Reliable Data Transfer (RDT): Stop-and-Wait, Go-Back-N, and Selective Repeat over UDP.
                   • Adaptive RTO: RFC 6298 formulas (EstimatedRTT, DevRTT, Karn's exponential backoff).
                   • Flow Control: Receiver-advertised window in custom RDT packet header.
                   • Congestion Control: AIMD, Slow Start, Congestion Avoidance, Fast Retransmit (Tahoe vs Reno).
                   • Network Simulation: LossyChannel with packet loss %, delay, jitter, and corruption.

                3. NETWORK LAYER
                   • IPv4 Addressing & Subnetting: Classful (A/B/C) and CIDR calculator (/8 to /32).
                   • VLSM (Variable Length Subnet Masking): Allocation tree algorithm based on host demands.
                   • IPv4 Header Construction: 20-byte RFC 791 header with RFC 1071 Internet Checksum.
                   • Fragmentation & Reassembly: Path MTU splitting in 8-byte units, MF flag, and reassembly.
                   • Routing Algorithms:
                     - Link-State (Dijkstra): Shortest path tree with iteration snapshots.
                     - Distance-Vector (Bellman-Ford): Distributed iteration tables.
                     - Count-to-Infinity: Routing loop simulation and Split Horizon / Poison Reverse fix.
                   • Supplementary: NAT translation table and ARP cache simulation.

                4. SYSTEM INTEGRITY & BENCHMARKING
                   • End-to-End File Verification: SHA-256 cryptographic hashing.
                   • Performance Comparison: Automated stress tests across client counts (1 to 200).
                ========================================================================================
                """);
        tab.setContent(new VBox(area));
        return tab;
    }

    private void applyDarkTheme(Scene scene) {
        scene.getRoot().setStyle("-fx-base: #1e1e24; -fx-background: #18181c; -fx-control-inner-background: #25252b; -fx-text-fill: #e0e0e0; -fx-font-family: 'Segoe UI', 'Helvetica', sans-serif;");
    }

    private void cleanup() {
        if (embeddedClient != null) embeddedClient.disconnect();
        if (embeddedServer != null) embeddedServer.stop();
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
