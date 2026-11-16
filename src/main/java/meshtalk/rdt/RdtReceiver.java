package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.MeshLogger;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for RDT receivers over UDP.
 * Listens on a DatagramSocket (or LossyChannel), handles handshake SYN,
 * verifies checksums, writes received payloads to an output file,
 * and handles FIN teardown with SHA-256 verification.
 */
public abstract class RdtReceiver {

    protected final MeshLogger log = new MeshLogger(getClass().getSimpleName());
    protected final DatagramSocket socket;
    protected final int windowSize;
    protected final RdtEventListener listener;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    public RdtReceiver(DatagramSocket socket, int windowSize, RdtEventListener listener) {
        this.socket = socket;
        this.windowSize = windowSize;
        this.listener = (listener != null) ? listener : RdtEventListener.NOOP;
    }

    /**
     * Waits for SYN and responds with SYN_ACK.
     */
    protected SocketAddress handleHandshake() throws IOException {
        log.info("Listening for connection (SYN)...");
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);

        while (running.get()) {
            try {
                socket.receive(pkt);
                byte[] raw = Arrays.copyOf(pkt.getData(), pkt.getLength());
                RdtPacket syn = RdtPacket.fromBytes(raw);
                if (syn.verifyChecksum() && syn.getType() == RdtPacket.Type.SYN) {
                    log.info("SYN received from %s. Sending SYN_ACK...", pkt.getSocketAddress());
                    RdtPacket synAck = RdtPacket.synAck();
                    byte[] synAckBytes = synAck.toBytes();
                    socket.send(new DatagramPacket(synAckBytes, synAckBytes.length, pkt.getSocketAddress()));
                    return pkt.getSocketAddress();
                }
            } catch (SocketTimeoutException ignored) {}
        }
        return null;
    }

    /**
     * Core reception logic implemented by subclasses.
     */
    public abstract void receiveFile(File outputFile) throws IOException;

    public void stop() {
        running.set(false);
    }
}
