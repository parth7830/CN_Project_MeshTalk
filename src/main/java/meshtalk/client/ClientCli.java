package meshtalk.client;

import meshtalk.common.Constants;
import meshtalk.nettools.RoutingSimulator;
import meshtalk.nettools.SubnetCalculator;
import meshtalk.rdt.CongestionController;
import meshtalk.rdt.RdtEventListener;
import meshtalk.rdt.RdtFileTransfer;
import meshtalk.server.ConcurrencyDemo;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;

/**
 * Full Command-Line Interface (CLI) for MeshTalk.
 * Provides access to every feature from the terminal without requiring JavaFX.
 */
public class ClientCli implements TcpClient.MessageListener {

    private final TcpClient client;

    public ClientCli(String host, int port) {
        this.client = new TcpClient(host, port);
        this.client.setMessageListener(this);
    }

    @Override
    public void onMessage(String sender, String text) {
        System.out.println(String.format("\n[CHAT] %s: %s", sender, text));
        prompt();
    }

    @Override
    public void onPrivateMessage(String sender, String text) {
        System.out.println(String.format("\n[PRIVATE from %s]: %s", sender, text));
        prompt();
    }

    @Override
    public void onUserJoined(String user) {
        System.out.println(String.format("\n*** %s joined the chat ***", user));
        prompt();
    }

    @Override
    public void onUserLeft(String user) {
        System.out.println(String.format("\n*** %s left the chat ***", user));
        prompt();
    }

    @Override
    public void onUserList(List<String> users) {
        System.out.println("\n--- Online Users (" + users.size() + ") ---");
        for (String u : users) {
            System.out.println("  • " + u);
        }
        prompt();
    }

    @Override
    public void onHistory(List<String> history) {
        System.out.println("\n--- Recent Chat History ---");
        for (String line : history) {
            System.out.println("  " + line);
        }
        prompt();
    }

    @Override
    public void onFileOffer(String sender, String filename, long size) {
        System.out.println(String.format("\n[FILE INCOMING] Receiving '%s' (%d bytes) from %s...", filename, size, sender));
        prompt();
    }

    @Override
    public void onFileProgress(String filename, double progress) {
        System.out.printf("\r[FILE PROGRESS] %s: %.1f%%", filename, progress * 100);
    }

    @Override
    public void onFileComplete(String filename, boolean verified) {
        System.out.println(String.format("\n[FILE COMPLETE] %s -> %s", filename, verified ? "VERIFIED (SHA-256 MATCH)" : "FAILED CHECKSUM!"));
        prompt();
    }

    @Override
    public void onRoomMessage(String room, String sender, String text) {
        System.out.println(String.format("\n[%s] %s: %s", room, sender, text));
        prompt();
    }

    @Override
    public void onError(String error) {
        System.err.println(String.format("\n[ERROR] %s", error));
        prompt();
    }

    @Override
    public void onDisconnect() {
        System.out.println("\n[SYSTEM] Disconnected from server.");
    }

    private static void prompt() {
        System.out.print("> ");
        System.out.flush();
    }

    public void run(String requestedUser) {
        Scanner scanner = new Scanner(System.in);
        String username = requestedUser;

        while (username == null || username.trim().isEmpty()) {
            System.out.print("Enter username: ");
            username = scanner.nextLine().trim();
        }

        try {
            boolean loggedIn = client.connectAndLogin(username);
            if (!loggedIn) {
                System.out.println("Could not log in. Exiting.");
                return;
            }
        } catch (Exception e) {
            System.err.println("Connection error: " + e.getMessage());
            return;
        }

        printHelp();
        prompt();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                prompt();
                continue;
            }

            try {
                if (line.startsWith("/")) {
                    handleCommand(line);
                    if ("/quit".equalsIgnoreCase(line)) break;
                } else {
                    client.sendMessage(line);
                }
            } catch (Exception e) {
                System.err.println("Command failed: " + e.getMessage());
            }
            prompt();
        }

        client.disconnect();
    }

    private void handleCommand(String line) throws Exception {
        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/help":
                printHelp();
                break;

            case "/pm":
                if (parts.length < 3) {
                    System.out.println("Usage: /pm <user> <message>");
                } else {
                    client.sendPrivateMessage(parts[1], parts[2]);
                }
                break;

            case "/users":
                client.requestUserList();
                break;

            case "/file":
                if (parts.length < 3) {
                    System.out.println("Usage: /file <user> <path>");
                } else {
                    File f = new File(parts[2]);
                    client.sendFile(parts[1], f);
                }
                break;

            case "/ping": {
                long rtt = UdpClient.ping("localhost", Constants.DEFAULT_UDP_PORT, 2000);
                System.out.printf("[UDP PING] RTT = %d ms%n", rtt);
                break;
            }

            case "/discover": {
                UdpClient.DiscoveryResult res = UdpClient.discoverServer(2000);
                if (res != null) {
                    System.out.println("[DISCOVERY] Found MeshTalk Server at: " + res);
                } else {
                    System.out.println("[DISCOVERY] No server discovered on LAN.");
                }
                break;
            }

            case "/status": {
                String status = UdpClient.requestStatus("localhost", Constants.DEFAULT_UDP_PORT, 2000);
                System.out.println("[SERVER STATUS]\n" + status);
                break;
            }

            case "/subnet": {
                if (parts.length < 2) {
                    System.out.println("Usage: /subnet <ip/prefix> (e.g. /subnet 192.168.1.50/24)");
                } else {
                    SubnetCalculator.SubnetInfo info = SubnetCalculator.calculate(parts[1]);
                    System.out.println(info);
                }
                break;
            }

            case "/route": {
                System.out.println("Running Dijkstra on textbook topology...");
                RoutingSimulator.Topology top = RoutingSimulator.Topology.createKuroseTopology();
                RoutingSimulator.DijkstraResult dr = RoutingSimulator.dijkstra(top, "u");
                System.out.println("Routing table for node u:");
                for (RoutingSimulator.RouteEntry re : dr.routingTable.values()) {
                    System.out.println("  " + re);
                }
                break;
            }

            case "/concurrency": {
                System.out.println(ConcurrencyDemo.demonstrateRaceCondition(8, 10000));
                System.out.println(ConcurrencyDemo.demonstrateDeadlock(false));
                System.out.println(ConcurrencyDemo.demonstrateDeadlock(true));
                break;
            }

            case "/rdt": {
                System.out.println("Testing RDT Selective Repeat file transfer over UDP with 10% simulated loss...");
                File tempIn = File.createTempFile("rdt_cli_test", ".txt");
                java.nio.file.Files.writeString(tempIn.toPath(), "Hello RDT over UDP with packet loss! University Computer Networks PBL.");
                File tempOut = File.createTempFile("rdt_cli_out", ".txt");

                RdtFileTransfer.TransferResult res = RdtFileTransfer.runLocalTransfer(
                        tempIn, tempOut,
                        RdtFileTransfer.ProtocolType.SELECTIVE_REPEAT, 8,
                        0.10, 0.0, 5,
                        CongestionController.Mode.RENO,
                        RdtEventListener.NOOP
                );
                System.out.println(res);
                tempIn.delete();
                tempOut.delete();
                break;
            }

            case "/quit":
                System.out.println("Exiting MeshTalk CLI...");
                break;

            default:
                System.out.println("Unknown command: " + cmd + ". Type /help for command list.");
                break;
        }
    }

    private static void printHelp() {
        System.out.println("==================================================");
        System.out.println("MeshTalk Commands:");
        System.out.println("  <text>                  - Broadcast message to all");
        System.out.println("  /pm <user> <message>    - Send private message");
        System.out.println("  /users                  - List online users");
        System.out.println("  /file <user> <path>     - Send file over TCP (SHA-256)");
        System.out.println("  /rdt                    - Run RDT UDP transfer demo (10% loss)");
        System.out.println("  /ping                   - Measure UDP echo RTT");
        System.out.println("  /discover               - Auto-discover server on LAN");
        System.out.println("  /status                 - Get server metrics JSON");
        System.out.println("  /subnet <ip/prefix>     - Calculate subnet details");
        System.out.println("  /route                  - Run Dijkstra & DV routing demo");
        System.out.println("  /concurrency            - Run race condition & deadlock demo");
        System.out.println("  /help                   - Show this menu");
        System.out.println("  /quit                   - Exit client");
        System.out.println("==================================================");
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = Constants.DEFAULT_TCP_PORT;
        String user = null;

        for (int i = 0; i < args.length; i++) {
            if ("--host".equals(args[i]) && i + 1 < args.length) {
                host = args[++i];
            } else if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--user".equals(args[i]) && i + 1 < args.length) {
                user = args[++i];
            }
        }

        ClientCli cli = new ClientCli(host, port);
        cli.run(user);
    }
}
