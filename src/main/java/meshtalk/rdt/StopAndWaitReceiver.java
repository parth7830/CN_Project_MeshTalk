package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;

import java.io.*;
import java.net.*;
import java.util.Arrays;

/**
 * Stop-and-Wait Receiver.
 * Acknowledges valid in-order packets with ACK(seqNo).
 * If corrupted or out-of-order, resends previous ACK or drops.
 */
public class StopAndWaitReceiver extends RdtReceiver {

    public StopAndWaitReceiver(DatagramSocket socket, RdtEventListener listener) {
        super(socket, 1, listener);
    }

    @Override
    public void receiveFile(File outputFile) throws IOException {
        running.set(true);
        socket.setSoTimeout(500);

        SocketAddress senderAddr = handleHandshake();
        if (senderAddr == null) return;

        ByteArrayOutputStream receivedData = new ByteArrayOutputStream();
        int expectedSeq = 0;
        byte[] buf = new byte[4096];
        DatagramPacket recvPkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                socket.receive(recvPkt);
                byte[] raw = Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
                RdtPacket packet = RdtPacket.fromBytes(raw);

                // Verify Checksum
                if (!packet.verifyChecksum()) {
                    log.warn("Checksum failure on received packet! Discarding.");
                    continue;
                }

                if (packet.getType() == RdtPacket.Type.DATA) {
                    if (packet.getSeqNo() == expectedSeq) {
                        receivedData.write(packet.getPayload());
                        RdtPacket ack = RdtPacket.ack(expectedSeq, 1);
                        byte[] ackBytes = ack.toBytes();
                        socket.send(new DatagramPacket(ackBytes, ackBytes.length, recvPkt.getSocketAddress()));
                        expectedSeq++;
                    } else if (packet.getSeqNo() < expectedSeq) {
                        // Duplicate from sender who missed our previous ACK
                        RdtPacket ack = RdtPacket.ack(packet.getSeqNo(), 1);
                        byte[] ackBytes = ack.toBytes();
                        socket.send(new DatagramPacket(ackBytes, ackBytes.length, recvPkt.getSocketAddress()));
                    }
                } else if (packet.getType() == RdtPacket.Type.FIN) {
                    log.info("FIN received. Sending FIN_ACK and verifying file...");
                    RdtPacket finAck = RdtPacket.finAck();
                    byte[] finAckBytes = finAck.toBytes();
                    socket.send(new DatagramPacket(finAckBytes, finAckBytes.length, recvPkt.getSocketAddress()));

                    // Write file and verify
                    byte[] finalBytes = receivedData.toByteArray();
                    if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(finalBytes);
                    }

                    String computedSha = ChecksumUtil.sha256Hex(finalBytes);
                    String receivedSha = new String(packet.getPayload()).trim();
                    boolean verified = computedSha.equalsIgnoreCase(receivedSha);
                    log.info("File verification: computed=%s, expected=%s -> %s",
                            computedSha, receivedSha, verified ? "VERIFIED" : "MISMATCH");
                    break;
                }
            } catch (SocketTimeoutException ignored) {}
        }
    }
}
