package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;

import java.io.*;
import java.net.*;
import java.util.Arrays;

/**
 * Go-Back-N (GBN) Receiver.
 * Uses cumulative ACKs. Delivers in-order packets directly.
 * Discards out-of-order packets and resends ACK for last in-order packet.
 */
public class GbnReceiver extends RdtReceiver {

    public GbnReceiver(DatagramSocket socket, int windowSize, RdtEventListener listener) {
        super(socket, windowSize, listener);
    }

    @Override
    public void receiveFile(File outputFile) throws IOException {
        running.set(true);
        socket.setSoTimeout(500);

        SocketAddress senderAddr = handleHandshake();
        if (senderAddr == null) return;

        ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
        int expectedSeqNum = 0;
        int lastAckSent = -1;

        byte[] buf = new byte[4096];
        DatagramPacket recvPkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                socket.receive(recvPkt);
                byte[] raw = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
                RdtPacket packet = RdtPacket.fromBytes(raw);

                if (!packet.verifyChecksum()) {
                    log.warn("Corrupted packet received (CRC mismatch). Dropping.");
                    continue;
                }

                if (packet.getType() == RdtPacket.Type.DATA) {
                    int seq = packet.getSeqNo();
                    if (seq == expectedSeqNum) {
                        receivedData.write(packet.getPayload());
                        lastAckSent = seq;
                        expectedSeqNum++;

                        RdtPacket ack = RdtPacket.ack(lastAckSent, windowSize);
                        byte[] ackBytes = ack.toBytes();
                        socket.send(new DatagramPacket(ackBytes, ackBytes.length, recvPkt.getSocketAddress()));
                    } else {
                        // Out of order: drop payload, resend ACK for last acknowledged packet
                        if (lastAckSent >= 0) {
                            RdtPacket ack = RdtPacket.ack(lastAckSent, windowSize);
                            byte[] ackBytes = ack.toBytes();
                            socket.send(new DatagramPacket(ackBytes, ackBytes.length, recvPkt.getSocketAddress()));
                        }
                    }
                } else if (packet.getType() == RdtPacket.Type.FIN) {
                    log.info("FIN received in GBN. Sending FIN_ACK.");
                    RdtPacket finAck = RdtPacket.finAck();
                    byte[] finAckBytes = finAck.toBytes();
                    socket.send(new DatagramPacket(finAckBytes, finAckBytes.length, recvPkt.getSocketAddress()));

                    byte[] finalBytes = receivedData.toByteArray();
                    if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(finalBytes);
                    }

                    String computedSha = ChecksumUtil.sha256Hex(finalBytes);
                    String receivedSha = new String(packet.getPayload()).trim();
                    log.info("GBN File: %s (computed=%s, received=%s)",
                            computedSha.equalsIgnoreCase(receivedSha) ? "VERIFIED" : "MISMATCH",
                            computedSha, receivedSha);
                    break;
                }
            } catch (SocketTimeoutException ignored) {}
        }
    }
}
