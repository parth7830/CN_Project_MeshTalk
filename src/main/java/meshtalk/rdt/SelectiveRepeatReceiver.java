package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Selective Repeat (SR) Receiver.
 * Buffers packets within the receive window [rcv_base, rcv_base + N - 1].
 * Sends individual ACKs for each received packet.
 * Delivers buffered consecutive packets once the base is filled.
 */
public class SelectiveRepeatReceiver extends RdtReceiver {

    public SelectiveRepeatReceiver(DatagramSocket socket, int windowSize, RdtEventListener listener) {
        super(socket, windowSize, listener);
    }

    @Override
    public void receiveFile(File outputFile) throws IOException {
        running.set(true);
        socket.setSoTimeout(500);

        SocketAddress senderAddr = handleHandshake();
        if (senderAddr == null) return;

        ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
        int rcvBase = 0;
        Map<Integer, byte[]> buffer = new HashMap<>();

        byte[] buf = new byte[4096];
        DatagramPacket recvPkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                socket.receive(recvPkt);
                byte[] raw = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
                RdtPacket packet = RdtPacket.fromBytes(raw);

                if (!packet.verifyChecksum()) {
                    log.warn("Selective Repeat: Checksum error on received packet. Dropping.");
                    continue;
                }

                if (packet.getType() == RdtPacket.Type.DATA) {
                    int seq = packet.getSeqNo();

                    // Send individual ACK for any packet in [rcvBase - N, rcvBase + N - 1]
                    if (seq >= Math.max(0, rcvBase - windowSize) && seq < rcvBase + windowSize) {
                        RdtPacket ack = RdtPacket.ack(seq, windowSize);
                        byte[] ackBytes = ack.toBytes();
                        socket.send(new DatagramPacket(ackBytes, ackBytes.length, recvPkt.getSocketAddress()));
                    }

                    // If packet is within current window [rcvBase, rcvBase + N - 1]
                    if (seq >= rcvBase && seq < rcvBase + windowSize) {
                        buffer.put(seq, packet.getPayload());

                        // Deliver in-order buffered packets
                        while (buffer.containsKey(rcvBase)) {
                            byte[] chunk = buffer.remove(rcvBase);
                            receivedData.write(chunk);
                            rcvBase++;
                        }
                    }
                } else if (packet.getType() == RdtPacket.Type.FIN) {
                    log.info("FIN received in Selective Repeat. Sending FIN_ACK.");
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
                    log.info("Selective Repeat File: %s (computed=%s, received=%s)",
                            computedSha.equalsIgnoreCase(receivedSha) ? "VERIFIED" : "MISMATCH",
                            computedSha, receivedSha);
                    break;
                }
            } catch (SocketTimeoutException ignored) {}
        }
    }
}
