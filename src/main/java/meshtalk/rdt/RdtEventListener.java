package meshtalk.rdt;

/**
 * Callback interface for monitoring RDT (Reliable Data Transfer) events.
 *
 * Implementations of this interface receive notifications about every
 * significant event during a file transfer — packet sends, ACK arrivals,
 * timeouts, retransmissions, congestion-window changes, and RTT samples.
 *
 * This is the primary hook for the UI / visualisation layer.
 */
public interface RdtEventListener {

    /** Called when a data packet is sent (or resent). */
    void onPacketSent(int seqNo, int size);

    /** Called when an ACK is received from the other side. */
    void onAckReceived(int ackNo);

    /** Called when a retransmission timer fires for a packet. */
    void onTimeout(int seqNo);

    /** Called when a packet is retransmitted. */
    void onRetransmit(int seqNo);

    /** Called when a duplicate ACK is detected. */
    void onDupAck(int ackNo, int count);

    /** Called whenever the congestion window or ssthresh changes. */
    void onCwndChange(double cwnd, double ssthresh);

    /** Called when a new RTT sample is measured. */
    void onRttSample(long rtt, long estimatedRtt, long rto);

    /**
     * Called when the entire transfer completes (or fails).
     *
     * @param totalTime       elapsed wall-clock time in milliseconds
     * @param totalBytes      total payload bytes transferred
     * @param retransmissions number of retransmitted packets
     * @param sha256          SHA-256 hex string of the transferred file
     */
    void onComplete(long totalTime, long totalBytes, int retransmissions, String sha256);

    // ── No-op default implementation ─────────────────────────────────

    /** A listener that silently ignores every event. */
    RdtEventListener NOOP = new RdtEventListener() {
        @Override public void onPacketSent(int seqNo, int size) {}
        @Override public void onAckReceived(int ackNo) {}
        @Override public void onTimeout(int seqNo) {}
        @Override public void onRetransmit(int seqNo) {}
        @Override public void onDupAck(int ackNo, int count) {}
        @Override public void onCwndChange(double cwnd, double ssthresh) {}
        @Override public void onRttSample(long rtt, long estimatedRtt, long rto) {}
        @Override public void onComplete(long totalTime, long totalBytes, int retransmissions, String sha256) {}
    };
}
