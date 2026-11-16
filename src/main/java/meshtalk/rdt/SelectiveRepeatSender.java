package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.Constants;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.util.*;

/**
 * Selective Repeat (SR) Sliding Window Sender.
 * Buffers packets in the sender window, tracks individual timeouts per packet,
 * and retransmits ONLY the lost/timed-out packets.
 */
public class SelectiveRepeatSender extends RdtSender {

    private static class PacketEntry {
        final int seqNo;
        final DatagramPacket datagram;
        final byte[] payload;
        long sendTime;
        boolean acked;

        PacketEntry(int seqNo, DatagramPacket datagram, byte[] payload, long sendTime) {
            this.seqNo = seqNo;
            this.datagram = datagram;
            this.payload = payload;
            this.sendTime = sendTime;
            this.acked = false;
        }
    }

    public SelectiveRepeatSender(LossyChannel channel, int windowSize,
                                 CongestionController.Mode ccMode, RdtEventListener listener) {
        super(channel, windowSize, ccMode, listener);
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

        int totalPackets = chunks.size();
        int base = 0;
        int nextSeqNum = 0;
        int retransmissions = 0;
        long totalBytesSent = 0;

        Map<Integer, PacketEntry> windowBuffer = new HashMap<>();
        channel.getSocket().setSoTimeout(30);

        while (base < totalPackets && running.get()) {
            int effectiveWindow = Math.min(windowSize, (int) Math.max(1, congestionController.getCwnd()));

            // Send new packets within window
            while (nextSeqNum < base + effectiveWindow && nextSeqNum < totalPackets && running.get()) {
                byte[] chunk = chunks.get(nextSeqNum);
                RdtPacket pkt = RdtPacket.data(nextSeqNum, chunk);
                byte[] raw = pkt.toBytes();
                DatagramPacket dg = new DatagramPacket(raw, raw.length, receiver);

                channel.send(dg);
                long now = System.currentTimeMillis();
                windowBuffer.put(nextSeqNum, new PacketEntry(nextSeqNum, dg, chunk, now));
                listener.onPacketSent(nextSeqNum, chunk.length);
                nextSeqNum++;
            }

            // Read ACKs
            try {
                DatagramPacket ackDg = channel.receive();
                byte[] raw = Arrays.copyOf(ackDg.getData(), ackDg.getLength());
                RdtPacket ackPkt = RdtPacket.fromBytes(raw);

                if (ackPkt.verifyChecksum() && ackPkt.getType() == RdtPacket.Type.ACK) {
                    int ackNo = ackPkt.getAckNo();
                    PacketEntry entry = windowBuffer.get(ackNo);
                    if (entry != null && !entry.acked) {
                        entry.acked = true;
                        long sampleRtt = System.currentTimeMillis() - entry.sendTime;
                        rttEstimator.addSample(sampleRtt);
                        congestionController.onAck();
                        listener.onAckReceived(ackNo);
                        listener.onRttSample(sampleRtt, (long) rttEstimator.getEstimatedRtt(), rttEstimator.getRto());
                        listener.onCwndChange(congestionController.getCwnd(), congestionController.getSsthresh());
                        totalBytesSent += entry.payload.length;

                        // Slide base forward if base was acked
                        while (base < totalPackets && windowBuffer.containsKey(base) && windowBuffer.get(base).acked) {
                            windowBuffer.remove(base);
                            base++;
                        }
                    }
                }
            } catch (SocketTimeoutException ignored) {}

            // Check individual packet timeouts
            long now = System.currentTimeMillis();
            long currentRto = rttEstimator.getRto();

            for (PacketEntry entry : windowBuffer.values()) {
                if (!entry.acked && (now - entry.sendTime) > currentRto) {
                    log.warn("Selective Repeat timeout on packet seq=%d. Retransmitting single packet.", entry.seqNo);
                    rttEstimator.timeout();
                    congestionController.onTimeout();
                    listener.onTimeout(entry.seqNo);
                    listener.onRetransmit(entry.seqNo);
                    listener.onCwndChange(congestionController.getCwnd(), congestionController.getSsthresh());

                    channel.send(entry.datagram);
                    entry.sendTime = now;
                    retransmissions++;
                }
            }
        }

        performTeardown(receiver, sha256);
        long totalTime = Math.max(1, System.currentTimeMillis() - startTime);
        listener.onComplete(totalTime, totalBytesSent, retransmissions, sha256);
    }
}
