package meshtalk.client;

import meshtalk.common.Constants;
import meshtalk.common.MeshLogger;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * UDP Utilities for MeshTalk clients:
 *  - Echo/Ping for round-trip latency measurement (RTT)
 *  - UDP broadcast server discovery on local LAN
 *  - Direct server status query (JSON metrics)
 */
public class UdpClient {

    private static final MeshLogger log = new MeshLogger("UdpClient");

    public static class DiscoveryResult {
        public final String host;
        public final int tcpPort;

        public DiscoveryResult(String host, int tcpPort) {
            this.host = host;
            this.tcpPort = tcpPort;
        }

        @Override
        public String toString() {
            return host + ":" + tcpPort;
        }
    }

    /**
     * Sends UDP ping packet to server and measures Round-Trip Time (RTT) in ms.
     */
    public static long ping(String host, int udpPort, int timeoutMs) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);

            String pingMsg = "PING:" + System.currentTimeMillis();
            byte[] sendBuf = pingMsg.getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(host);
            DatagramPacket sendPkt = new DatagramPacket(sendBuf, sendBuf.length, addr, udpPort);

            long start = System.nanoTime();
            socket.send(sendPkt);

            byte[] recvBuf = new byte[1024];
            DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(recvPkt);
            long end = System.nanoTime();

            return (end - start) / 1_000_000;
        }
    }

    /**
     * Discovers a MeshTalk server on the local network using UDP broadcast.
     */
    public static DiscoveryResult discoverServer(int timeoutMs) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(timeoutMs);

            byte[] sendBuf = Constants.DISCOVERY_MAGIC.getBytes(StandardCharsets.UTF_8);
            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket sendPkt = new DatagramPacket(sendBuf, sendBuf.length, broadcastAddr, Constants.DEFAULT_UDP_PORT);

            socket.send(sendPkt);
            log.info("Broadcasted discovery query on UDP %d...", Constants.DEFAULT_UDP_PORT);

            byte[] recvBuf = new byte[1024];
            DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(recvPkt);

            String resp = new String(recvPkt.getData(), 0, recvPkt.getLength(), StandardCharsets.UTF_8).trim();
            if (resp.startsWith(Constants.DISCOVERY_RESPONSE)) {
                String[] parts = resp.split(":");
                int tcpPort = (parts.length > 1) ? Integer.parseInt(parts[1]) : Constants.DEFAULT_TCP_PORT;
                String serverHost = recvPkt.getAddress().getHostAddress();
                return new DiscoveryResult(serverHost, tcpPort);
            }
        }
        return null;
    }

    /**
     * Queries server for live JSON metrics via UDP.
     */
    public static String requestStatus(String host, int udpPort, int timeoutMs) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);

            byte[] sendBuf = "STATUS".getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(host);
            DatagramPacket sendPkt = new DatagramPacket(sendBuf, sendBuf.length, addr, udpPort);

            socket.send(sendPkt);

            byte[] recvBuf = new byte[4096];
            DatagramPacket recvPkt = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(recvPkt);

            return new String(recvPkt.getData(), 0, recvPkt.getLength(), StandardCharsets.UTF_8).trim();
        }
    }
}
