package meshtalk.server;

import meshtalk.common.Constants;
import meshtalk.common.MeshLogger;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight UDP companion service for the TCP server.
 * Handles:
 *  - UDP Echo / ping for measuring network RTT
 *  - Server status queries (metrics JSON)
 *  - LAN Discovery (responding to broadcast DISCOVERY_MAGIC)
 */
public class UdpService implements Runnable {

    private static final MeshLogger log = new MeshLogger("UdpService");

    private final int port;
    private final int tcpPort;
    private final ServerMetrics metrics;
    private DatagramSocket socket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public UdpService(int udpPort, int tcpPort, ServerMetrics metrics) {
        this.port = udpPort;
        this.tcpPort = tcpPort;
        this.metrics = metrics;
    }

    public synchronized void start() throws SocketException {
        if (running.get()) return;
        socket = new DatagramSocket(port);
        running.set(true);
        workerThread = new Thread(this, "MeshTalk-UdpService");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("UDP Service listening on port %d (announcing TCP port %d)", port, tcpPort);
    }

    public synchronized void stop() {
        running.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    @Override
    public void run() {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (running.get() && !socket.isClosed()) {
            try {
                socket.receive(pkt);
                String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8).trim();

                if (Constants.DISCOVERY_MAGIC.equals(msg)) {
                    // LAN discovery request: reply with DISCOVERY_RESPONSE:tcpPort
                    String reply = Constants.DISCOVERY_RESPONSE + ":" + tcpPort;
                    byte[] replyBytes = reply.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket replyPkt = new DatagramPacket(replyBytes, replyBytes.length, pkt.getSocketAddress());
                    socket.send(replyPkt);
                    log.debug("Answered discovery request from %s", pkt.getSocketAddress());

                } else if ("STATUS".equalsIgnoreCase(msg)) {
                    // Server stats request
                    String json = metrics.toJson();
                    byte[] replyBytes = json.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket replyPkt = new DatagramPacket(replyBytes, replyBytes.length, pkt.getSocketAddress());
                    socket.send(replyPkt);

                } else if (msg.startsWith("PING")) {
                    // Echo back for RTT measurement
                    String pong = "PONG:" + (msg.length() > 4 ? msg.substring(5) : "");
                    byte[] replyBytes = pong.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket replyPkt = new DatagramPacket(replyBytes, replyBytes.length, pkt.getSocketAddress());
                    socket.send(replyPkt);
                }

            } catch (SocketException se) {
                if (!running.get()) break;
            } catch (IOException e) {
                log.debug("UDP I/O error: %s", e.getMessage());
            }
        }
    }
}
