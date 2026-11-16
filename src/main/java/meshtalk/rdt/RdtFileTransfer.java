package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.MeshLogger;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-level coordinator for RDT file transfers.
 * Can launch transfers with configurable protocols (Stop-and-Wait, GBN, Selective Repeat),
 * loss rates, corruption rates, delay, and window sizes.
 */
public class RdtFileTransfer {

    private static final MeshLogger log = new MeshLogger("RdtFileTransfer");

    public enum ProtocolType {
        STOP_AND_WAIT,
        GO_BACK_N,
        SELECTIVE_REPEAT
    }

    public static class TransferResult {
        public boolean success;
        public long elapsedMs;
        public long totalBytes;
        public int retransmissions;
        public double throughputKBps;
        public String originalSha256;
        public String receivedSha256;
        public boolean verified;

        @Override
        public String toString() {
            return String.format("TransferResult: success=%b, time=%dms, bytes=%d, retrans=%d, throughput=%.2f KB/s, verified=%b",
                    success, elapsedMs, totalBytes, retransmissions, throughputKBps, verified);
        }
    }

    /**
     * Executes an end-to-end local file transfer over UDP between sender and receiver.
     */
    public static TransferResult runLocalTransfer(File sourceFile, File destFile,
                                                 ProtocolType protocol, int windowSize,
                                                 double lossRate, double corruptionRate, int delayMs,
                                                 CongestionController.Mode ccMode,
                                                 RdtEventListener listener) throws Exception {

        TransferResult result = new TransferResult();
        byte[] originalBytes = Files.readAllBytes(sourceFile.toPath());
        result.originalSha256 = ChecksumUtil.sha256Hex(originalBytes);
        result.totalBytes = originalBytes.length;

        DatagramSocket recvSocket = new DatagramSocket(0); // auto-assign port
        int receiverPort = recvSocket.getLocalPort();
        InetSocketAddress receiverAddr = new InetSocketAddress("127.0.0.1", receiverPort);

        DatagramSocket sendSocket = new DatagramSocket(0);
        LossyChannel channel = new LossyChannel(sendSocket);
        channel.setLossRate(lossRate);
        channel.setCorruptionRate(corruptionRate);
        channel.setDelayMs(delayMs);

        AtomicInteger retransmissionsCount = new AtomicInteger(0);

        RdtEventListener compositeListener = new RdtEventListener() {
            @Override public void onPacketSent(int seqNo, int size) {
                if (listener != null) listener.onPacketSent(seqNo, size);
            }
            @Override public void onAckReceived(int ackNo) {
                if (listener != null) listener.onAckReceived(ackNo);
            }
            @Override public void onTimeout(int seqNo) {
                if (listener != null) listener.onTimeout(seqNo);
            }
            @Override public void onRetransmit(int seqNo) {
                retransmissionsCount.incrementAndGet();
                if (listener != null) listener.onRetransmit(seqNo);
            }
            @Override public void onDupAck(int ackNo, int count) {
                if (listener != null) listener.onDupAck(ackNo, count);
            }
            @Override public void onCwndChange(double cwnd, double ssthresh) {
                if (listener != null) listener.onCwndChange(cwnd, ssthresh);
            }
            @Override public void onRttSample(long rtt, long estimatedRtt, long rto) {
                if (listener != null) listener.onRttSample(rtt, estimatedRtt, rto);
            }
            @Override public void onComplete(long totalTime, long totalBytes, int retransmissions, String sha256) {
                result.retransmissions = retransmissions;
                if (listener != null) listener.onComplete(totalTime, totalBytes, retransmissions, sha256);
            }
        };

        // Instantiate receiver
        RdtReceiver receiver;
        switch (protocol) {
            case STOP_AND_WAIT:
                receiver = new StopAndWaitReceiver(recvSocket, compositeListener);
                break;
            case GO_BACK_N:
                receiver = new GbnReceiver(recvSocket, windowSize, compositeListener);
                break;
            case SELECTIVE_REPEAT:
            default:
                receiver = new SelectiveRepeatReceiver(recvSocket, windowSize, compositeListener);
                break;
        }

        // Instantiate sender
        RdtSender sender;
        switch (protocol) {
            case STOP_AND_WAIT:
                sender = new StopAndWaitSender(channel, ccMode, compositeListener);
                break;
            case GO_BACK_N:
                sender = new GbnSender(channel, windowSize, ccMode, compositeListener);
                break;
            case SELECTIVE_REPEAT:
            default:
                sender = new SelectiveRepeatSender(channel, windowSize, ccMode, compositeListener);
                break;
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        long startTime = System.currentTimeMillis();

        Future<?> recvFuture = executor.submit(() -> {
            try {
                receiver.receiveFile(destFile);
            } catch (Exception e) {
                log.error("Receiver error: %s", e.getMessage());
            }
        });

        Future<?> sendFuture = executor.submit(() -> {
            try {
                sender.sendFile(sourceFile, receiverAddr);
            } catch (Exception e) {
                log.error("Sender error: %s", e.getMessage());
            }
        });

        try {
            sendFuture.get(60, TimeUnit.SECONDS);
            recvFuture.get(10, TimeUnit.SECONDS);
            result.success = true;
        } catch (Exception e) {
            log.error("Transfer timed out or failed: %s", e.getMessage());
            result.success = false;
        } finally {
            executor.shutdownNow();
            recvSocket.close();
            channel.close();
        }

        if (result.retransmissions == 0) {
            result.retransmissions = retransmissionsCount.get();
        }

        result.elapsedMs = Math.max(1, System.currentTimeMillis() - startTime);
        result.throughputKBps = (result.totalBytes / 1024.0) / (result.elapsedMs / 1000.0);

        if (destFile.exists()) {
            byte[] destBytes = Files.readAllBytes(destFile.toPath());
            result.receivedSha256 = ChecksumUtil.sha256Hex(destBytes);
            result.verified = result.originalSha256.equalsIgnoreCase(result.receivedSha256);
        } else {
            result.verified = false;
        }

        return result;
    }
}
