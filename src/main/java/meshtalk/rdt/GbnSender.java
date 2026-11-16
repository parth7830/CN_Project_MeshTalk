package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.Constants;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.util.*;

/**
 * Go-Back-N (GBN) Sliding Window Sender.
 * Uses cumulative ACKs and retransmits all unacknowledged packets starting from base upon timeout.
 * Also supports Fast Retransmit on 3 duplicate ACKs.
 */
public class GbnSender extends RdtSender {

    public GbnSender(LossyChannel channel, int windowSize,
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

        int lastAckNo = -1;
        int dupAckCount = 0;

        // Map seqNo -> DatagramPacket
        Map<Integer, DatagramPacket> sentPackets = new HashMap<>();
        Map<Integer, Long> sendTimes = new HashMap<>();

        long timerStart = 0;
        boolean timerRunning = false;

        channel.getSocket().setSoTimeout(50); // non-blocking poll with 50ms intervals

        while (base < totalPackets && running.get()) {
            int effectiveWindow = Math.min(windowSize, (int) Math.max(1, congestionController.getCwnd()));

            // Send up to window limit
            while (nextSeqNum < base + effectiveWindow && nextSeqNum < totalPackets && running.get()) {
                byte[] chunk = chunks.get(nextSeqNum);
                RdtPacket pkt = RdtPacket.data(nextSeqNum, chunk);
                byte[] raw = pkt.toBytes();
                DatagramPacket dg = new DatagramPacket(raw, raw.length, receiver);

                channel.send(dg);
                sentPackets.put(nextSeqNum, dg);
                sendTimes.put(nextSeqNum, System.currentTimeMillis());
                listener.onPacketSent(nextSeqNum, chunk.length);

                if (!timerRunning) {
                    timerStart = System.currentTimeMillis();
                    timerRunning = true;
                }
                nextSeqNum++;
            }

            // Check for incoming ACKs
            try {
                DatagramPacket ackDatagram = channel.receive();
                byte[] raw = Arrays.copyOf(ackDatagram.getData(), ackDatagram.getLength());
                RdtPacket ackPkt = RdtPacket.fromBytes(raw);

                if (ackPkt.verifyChecksum() && ackPkt.getType() == RdtPacket.Type.ACK) {
                    int ackNo = ackPkt.getAckNo();

                    if (ackNo >= base) {
                        // Cumulative ACK advances base
                        for (int i = base; i <= ackNo; i++) {
                            Long sentTime = sendTimes.remove(i);
                            sentPackets.remove(i);
                            if (sentTime != null && i == ackNo) {
                                long sampleRtt = System.currentTimeMillis() - sentTime;
                                rttEstimator.addSample(sampleRtt);
                                listener.onRttSample(sampleRtt, (long) rttEstimator.getEstimatedRtt(), rttEstimator.getRto());
                            }
                            if (i < chunks.size()) totalBytesSent += chunks.get(i).length;
                        }

                        base = ackNo + 1;
                        lastAckNo = ackNo;
                        dupAckCount = 0;
                        congestionController.onAck();
                        listener.onAckReceived(ackNo);
                        listener.onCwndChange(congestionController.getCwnd(), congestionController.getSsthresh());

                        if (base == nextSeqNum) {
                            timerRunning = false;
                        } else {
                            timerStart = System.currentTimeMillis();
                        }
                    } else if (ackNo == lastAckNo) {
                        // Duplicate ACK
                        dupAckCount++;
                        listener.onDupAck(ackNo, dupAckCount);
                        if (dupAckCount >= Constants.DUP_ACK_THRESHOLD) {
                            log.info("Fast Retransmit triggered on 3 dup ACKs for seq=%d", base);
                            congestionController.onTripleDupAck();
                            listener.onRetransmit(base);
                            listener.onCwndChange(congestionController.getCwnd(), congestionController.getSsthresh());
                            DatagramPacket retransPkt = sentPackets.get(base);
                            if (retransPkt != null) {
                                channel.send(retransPkt);
                                retransmissions++;
                            }
                            dupAckCount = 0;
                        }
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Read poll timeout, proceed to timer check
            }

            // Check base timeout
            if (timerRunning && (System.currentTimeMillis() - timerStart) > rttEstimator.getRto()) {
                log.warn("GBN Timeout on base=%d (RTO=%dms). Retransmitting window [%d, %d)...",
                        base, rttEstimator.getRto(), base, nextSeqNum);
                rttEstimator.timeout();
                congestionController.onTimeout();
                listener.onTimeout(base);
                listener.onCwndChange(congestionController.getCwnd(), congestionController.getSsthresh());

                // Retransmit all unacked packets
                for (int i = base; i < nextSeqNum; i++) {
                    DatagramPacket retrans = sentPackets.get(i);
                    if (retrans != null) {
                        channel.send(retrans);
                        retransmissions++;
                        listener.onRetransmit(i);
                    }
                }
                timerStart = System.currentTimeMillis();
            }
        }

        performTeardown(receiver, sha256);
        long totalTime = Math.max(1, System.currentTimeMillis() - startTime);
        listener.onComplete(totalTime, totalBytesSent, retransmissions, sha256);
    }
}
