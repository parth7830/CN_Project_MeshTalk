package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.Constants;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Stop-and-Wait RDT Sender over UDP.
 * Sends one packet and waits for matching ACK before advancing seqNo.
 */
public class StopAndWaitSender extends RdtSender {

    public StopAndWaitSender(LossyChannel channel, CongestionController.Mode ccMode, RdtEventListener listener) {
        super(channel, 1, ccMode, listener);
    }

    @Override
    public void sendFile(File file, InetSocketAddress receiver) throws IOException {
        running.set(true);
        long startTime = System.currentTimeMillis();
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        String sha256 = ChecksumUtil.sha256Hex(fileBytes);
        List<byte[]> chunks = prepareChunks(file);

        if (!performHandshake(receiver)) {
            throw new IOException("Connection handshake failed with " + receiver);
        }

        int seqNo = 0;
        int retransmissions = 0;
        long totalPayloadSent = 0;

        for (byte[] chunk : chunks) {
            if (!running.get()) break;

            RdtPacket packet = RdtPacket.data(seqNo, chunk);
            byte[] packetBytes = packet.toBytes();
            DatagramPacket sendDatagram = new DatagramPacket(packetBytes, packetBytes.length, receiver);

            boolean acked = false;
            while (!acked && running.get()) {
                long sendTime = System.currentTimeMillis();
                channel.send(sendDatagram);
                listener.onPacketSent(seqNo, chunk.length);

                try {
                    // Set socket timeout to current RTO
                    channel.getSocket().setSoTimeout((int) rttEstimator.getRto());
                    DatagramPacket ackDatagram = channel.receive();
                    byte[] rawAck = Arrays.copyOf(ackDatagram.getData(), ackDatagram.getLength());
                    RdtPacket ackPkt = RdtPacket.fromBytes(rawAck);

                    if (ackPkt.verifyChecksum() && ackPkt.getType() == RdtPacket.Type.ACK && ackPkt.getAckNo() == seqNo) {
                        acked = true;
                        long sampleRtt = System.currentTimeMillis() - sendTime;
                        rttEstimator.addSample(sampleRtt);
                        congestionController.onAck();
                        listener.onAckReceived(seqNo);
                        listener.onRttSample(sampleRtt, (long) rttEstimator.getEstimatedRtt(), rttEstimator.getRto());
                        listener.onCwndChange(congestionController.getCwnd(), congestionController.getSsthresh());
                        totalPayloadSent += chunk.length;
                    }
                } catch (SocketTimeoutException ste) {
                    retransmissions++;
                    rttEstimator.timeout();
                    congestionController.onTimeout();
                    listener.onTimeout(seqNo);
                    listener.onRetransmit(seqNo);
                    listener.onCwndChange(congestionController.getCwnd(), congestionController.getSsthresh());
                }
            }
            seqNo++;
        }

        performTeardown(receiver, sha256);
        long totalTime = Math.max(1, System.currentTimeMillis() - startTime);
        listener.onComplete(totalTime, totalPayloadSent, retransmissions, sha256);
    }
}
