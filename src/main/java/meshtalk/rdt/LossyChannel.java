package meshtalk.rdt;

import meshtalk.common.MeshLogger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A wrapper around {@link DatagramSocket} that simulates network impairments
 * on the <b>send</b> path.
 *
 * Supported impairments (all configurable at runtime):
 * <ul>
 *   <li><b>Loss</b> — packet is silently dropped</li>
 *   <li><b>Duplication</b> — packet is sent twice</li>
 *   <li><b>Corruption</b> — random bits are flipped in a copy</li>
 *   <li><b>Reordering</b> — packet is delayed by an extra random amount</li>
 *   <li><b>Fixed delay + jitter</b> — every packet is held before sending</li>
 * </ul>
 *
 * The <b>receive</b> side is left untouched — impairments only affect sends.
 */
public class LossyChannel {

    private static final MeshLogger log = new MeshLogger("LossyChannel");

    private final DatagramSocket socket;
    private final Random rng;

    // ── Impairment parameters ────────────────────────────────────────
    private volatile double lossRate       = 0.0;
    private volatile double duplicateRate  = 0.0;
    private volatile double corruptionRate = 0.0;
    private volatile double reorderRate    = 0.0;
    private volatile int    delayMs        = 0;
    private volatile int    jitterMs       = 0;

    // ── Statistics (thread-safe counters) ─────────────────────────────
    private final AtomicLong packetsSent       = new AtomicLong();
    private final AtomicLong packetsDropped    = new AtomicLong();
    private final AtomicLong packetsDuplicated = new AtomicLong();
    private final AtomicLong packetsCorrupted  = new AtomicLong();
    private final AtomicLong packetsReordered  = new AtomicLong();

    // ── Executor for delayed / reordered sends ───────────────────────
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LossyChannel-delay");
                t.setDaemon(true);
                return t;
            });

    // ── Constructors ─────────────────────────────────────────────────

    public LossyChannel(DatagramSocket socket) {
        this(socket, new Random());
    }

    public LossyChannel(DatagramSocket socket, Random rng) {
        this.socket = socket;
        this.rng = rng;
    }

    // ── Send with simulated impairments ──────────────────────────────

    /**
     * Send a DatagramPacket through the lossy channel.
     * Impairments are applied in this order:
     *   1. Loss check → maybe drop
     *   2. Corruption check → maybe flip bits in a copy
     *   3. Duplication check → maybe send twice
     *   4. Delay / reorder → maybe hold before sending
     */
    public void send(DatagramPacket pkt) throws IOException {
        // 1. Loss — just drop the packet
        if (lossRate > 0.0 && rng.nextDouble() < lossRate) {
            packetsDropped.incrementAndGet();
            log.debug("Simulated LOSS for packet to %s:%d",
                    pkt.getAddress(), pkt.getPort());
            return;
        }

        // 2. Corruption — flip some random bits in a COPY of the data
        byte[] data = copyPacketData(pkt);
        if (corruptionRate > 0.0 && rng.nextDouble() < corruptionRate) {
            corruptData(data);
            packetsCorrupted.incrementAndGet();
            log.debug("Simulated CORRUPTION for packet to %s:%d",
                    pkt.getAddress(), pkt.getPort());
        }

        // Build the (possibly corrupted) packet
        DatagramPacket toSend = new DatagramPacket(
                data, data.length, pkt.getAddress(), pkt.getPort());

        // 3. Reorder — add extra random delay
        boolean reorder = reorderRate > 0.0 && rng.nextDouble() < reorderRate;
        if (reorder) {
            packetsReordered.incrementAndGet();
        }

        // 4. Compute total delay
        int totalDelay = computeDelay(reorder);

        // Actually send (possibly after a delay)
        if (totalDelay > 0) {
            scheduler.schedule(() -> {
                try {
                    socket.send(toSend);
                    packetsSent.incrementAndGet();
                } catch (IOException e) {
                    log.error("Delayed send failed: %s", e.getMessage());
                }
            }, totalDelay, TimeUnit.MILLISECONDS);
        } else {
            socket.send(toSend);
            packetsSent.incrementAndGet();
        }

        // 5. Duplication — send an extra copy
        if (duplicateRate > 0.0 && rng.nextDouble() < duplicateRate) {
            packetsDuplicated.incrementAndGet();
            log.debug("Simulated DUPLICATE for packet to %s:%d",
                    pkt.getAddress(), pkt.getPort());
            DatagramPacket dup = new DatagramPacket(
                    data.clone(), data.length, pkt.getAddress(), pkt.getPort());
            int dupDelay = computeDelay(false);
            if (dupDelay > 0) {
                scheduler.schedule(() -> {
                    try {
                        socket.send(dup);
                        packetsSent.incrementAndGet();
                    } catch (IOException e) {
                        log.error("Duplicate send failed: %s", e.getMessage());
                    }
                }, dupDelay, TimeUnit.MILLISECONDS);
            } else {
                socket.send(dup);
                packetsSent.incrementAndGet();
            }
        }
    }

    // ── Receive (no simulation) ──────────────────────────────────────

    /**
     * Receive a DatagramPacket from the underlying socket.
     * No impairments are applied on the receive side.
     *
     * @return the received DatagramPacket
     * @throws IOException if the socket receive fails
     */
    public DatagramPacket receive() throws IOException {
        byte[] buf = new byte[65535];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
        return pkt;
    }

    // ── Internal helpers ─────────────────────────────────────────────

    /** Copy the meaningful bytes out of a DatagramPacket. */
    private byte[] copyPacketData(DatagramPacket pkt) {
        byte[] copy = new byte[pkt.getLength()];
        System.arraycopy(pkt.getData(), pkt.getOffset(), copy, 0, pkt.getLength());
        return copy;
    }

    /** Flip 1–3 random bits in the byte array. */
    private void corruptData(byte[] data) {
        if (data.length == 0) return;
        int flips = 1 + rng.nextInt(3); // flip 1 to 3 bits
        for (int i = 0; i < flips; i++) {
            int byteIdx = rng.nextInt(data.length);
            int bitIdx  = rng.nextInt(8);
            data[byteIdx] ^= (1 << bitIdx);
        }
    }

    /** Compute the total delay in ms (fixed + jitter + optional reorder). */
    private int computeDelay(boolean reorder) {
        int total = delayMs;
        if (jitterMs > 0) {
            total += rng.nextInt(2 * jitterMs + 1) - jitterMs; // −jitter … +jitter
        }
        if (reorder) {
            // Extra delay: 50–200 ms for reordering
            total += 50 + rng.nextInt(151);
        }
        return Math.max(total, 0);
    }

    // ── Configuration setters ────────────────────────────────────────

    public void setLossRate(double rate)       { this.lossRate = clamp(rate); }
    public void setDuplicateRate(double rate)   { this.duplicateRate = clamp(rate); }
    public void setCorruptionRate(double rate)  { this.corruptionRate = clamp(rate); }
    public void setReorderRate(double rate)     { this.reorderRate = clamp(rate); }
    public void setDelayMs(int ms)             { this.delayMs = Math.max(ms, 0); }
    public void setJitterMs(int ms)            { this.jitterMs = Math.max(ms, 0); }

    private double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    // ── Configuration getters ────────────────────────────────────────

    public double getLossRate()       { return lossRate; }
    public double getDuplicateRate() { return duplicateRate; }
    public double getCorruptionRate(){ return corruptionRate; }
    public double getReorderRate()   { return reorderRate; }
    public int    getDelayMs()       { return delayMs; }
    public int    getJitterMs()      { return jitterMs; }

    // ── Statistics getters ───────────────────────────────────────────

    public long getPacketsSent()       { return packetsSent.get(); }
    public long getPacketsDropped()    { return packetsDropped.get(); }
    public long getPacketsDuplicated() { return packetsDuplicated.get(); }
    public long getPacketsCorrupted()  { return packetsCorrupted.get(); }
    public long getPacketsReordered()  { return packetsReordered.get(); }

    // ── Lifecycle ────────────────────────────────────────────────────

    /** Shut down the delay scheduler and close the underlying socket. */
    public void close() {
        scheduler.shutdownNow();
        if (!socket.isClosed()) {
            socket.close();
        }
    }

    /** Return the underlying DatagramSocket (e.g. to set timeouts). */
    public DatagramSocket getSocket() {
        return socket;
    }
}
