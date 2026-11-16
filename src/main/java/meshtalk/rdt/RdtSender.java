package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.Constants;
import meshtalk.common.MeshLogger;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract base class for Reliable Data Transfer (RDT) senders over UDP.
 * Coordinates connection handshake (SYN/SYN_ACK), packet fragmentation,
 * retransmission, flow control, and teardown (FIN/FIN_ACK).
 */
public abstract class RdtSender {

    protected final MeshLogger log = new MeshLogger(getClass().getSimpleName());

    protected final LossyChannel channel;
    protected final RttEstimator rttEstimator = new RttEstimator();
    protected final CongestionController congestionController;
    protected final RdtEventListener listener;
    protected final int windowSize;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    public RdtSender(LossyChannel channel, int windowSize,
                     CongestionController.Mode ccMode, RdtEventListener listener) {
        this.channel = channel;
        this.windowSize = Math.max(1, windowSize);
        this.congestionController = new CongestionController(ccMode);
        this.listener = (listener != null) ? listener : RdtEventListener.NOOP;
    }

    /**
     * Performs SYN handshake with receiver.
     */
    protected boolean performHandshake(InetSocketAddress receiver) throws IOException {
        log.info("Starting 3-way handshake with %s...", receiver);
        channel.getSocket().setSoTimeout(300);
        RdtPacket syn = RdtPacket.syn();
        byte[] synBytes = syn.toBytes();
        DatagramPacket sendPkt = new DatagramPacket(synBytes, synBytes.length, receiver);

        byte[] buf = new byte[2048];
        DatagramPacket recvPkt = new DatagramPacket(buf, buf.length);

        for (int attempts = 0; attempts < 5; attempts++) {
            channel.send(sendPkt);
            try {
                // channel socket might have timeout
                DatagramPacket reply = channel.receive();
                byte[] data = Arrays.copyOf(reply.getData(), reply.getLength());
                RdtPacket resp = RdtPacket.fromBytes(data);
                if (resp.verifyChecksum() && resp.getType() == RdtPacket.Type.SYN_ACK) {
                    log.info("Handshake SYN_ACK received! Establishing connection.");
                    // Send ACK to complete 3-way handshake
                    RdtPacket ack = RdtPacket.ack(resp.getSeqNo(), windowSize);
                    byte[] ackBytes = ack.toBytes();
                    channel.send(new DatagramPacket(ackBytes, ackBytes.length, receiver));
                    return true;
                }
            } catch (SocketTimeoutException ste) {
                log.warn("Handshake SYN timeout, retrying (%d/5)...", attempts + 1);
            }
        }
        return false;
    }

    /**
     * Performs FIN teardown with receiver.
     */
    protected boolean performTeardown(InetSocketAddress receiver, String sha256Hex) throws IOException {
        log.info("Starting teardown (FIN) with hash: %s", sha256Hex);
        channel.getSocket().setSoTimeout(300);
        RdtPacket fin = RdtPacket.fin(sha256Hex.getBytes());
        byte[] finBytes = fin.toBytes();
        DatagramPacket sendPkt = new DatagramPacket(finBytes, finBytes.length, receiver);

        for (int attempts = 0; attempts < 5; attempts++) {
            channel.send(sendPkt);
            try {
                DatagramPacket reply = channel.receive();
                byte[] data = Arrays.copyOf(reply.getData(), reply.getLength());
                RdtPacket resp = RdtPacket.fromBytes(data);
                if (resp.verifyChecksum() && resp.getType() == RdtPacket.Type.FIN_ACK) {
                    log.info("Teardown FIN_ACK received! Transfer complete.");
                    return true;
                }
            } catch (SocketTimeoutException ste) {
                log.warn("Teardown FIN timeout, retrying (%d/5)...", attempts + 1);
            }
        }
        return false;
    }

    /**
     * Splits file into chunk payloads.
     */
    protected List<byte[]> prepareChunks(File file) throws IOException {
        byte[] allBytes = Files.readAllBytes(file.toPath());
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < allBytes.length) {
            int len = Math.min(Constants.RDT_MAX_PAYLOAD, allBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(allBytes, offset, offset + len);
            chunks.add(chunk);
            offset += len;
        }
        if (chunks.isEmpty()) {
            chunks.add(new byte[0]); // empty file
        }
        return chunks;
    }

    /**
     * Main transmission logic implemented by subclasses (StopAndWait, GBN, SR).
     */
    public abstract void sendFile(File file, InetSocketAddress receiver) throws IOException;

    public void stop() {
        running.set(false);
    }
}
