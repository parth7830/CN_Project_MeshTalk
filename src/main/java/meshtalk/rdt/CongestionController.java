package meshtalk.rdt;

import meshtalk.common.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simulates TCP congestion control (Tahoe and Reno variants).
 *
 * State machine:
 *   SLOW_START  → cwnd doubles every RTT (additive: +1 per ACK)
 *   CONGESTION_AVOIDANCE → cwnd grows by 1/cwnd per ACK (AIMD)
 *   FAST_RECOVERY (Reno only) → entered on triple dup-ACK
 *
 * Key events:
 *   onAck()           — normal new ACK received
 *   onTimeout()       — retransmission timer expired
 *   onTripleDupAck()  — 3 duplicate ACKs detected (fast retransmit trigger)
 */
public class CongestionController {

    // ── Congestion-control variant ───────────────────────────────────
    public enum Mode { TAHOE, RENO }

    // ── Internal state enum ──────────────────────────────────────────
    public enum State { SLOW_START, CONGESTION_AVOIDANCE, FAST_RECOVERY }

    // ── State ────────────────────────────────────────────────────────
    private final Mode mode;
    private double cwnd;        // congestion window (in units of MSS packets)
    private double ssthresh;    // slow-start threshold
    private State  state;

    // ── History for plotting ─────────────────────────────────────────
    // Each entry is {elapsedMs, cwnd}
    private final List<double[]> cwndHistory = new ArrayList<>();
    private final long startTime;

    public CongestionController(Mode mode) {
        this.mode      = mode;
        this.cwnd      = 1.0;    // start with 1 MSS
        this.ssthresh  = 64.0;   // initial ssthresh
        this.state     = State.SLOW_START;
        this.startTime = System.currentTimeMillis();
        recordHistory();
    }

    // ── Event handlers ───────────────────────────────────────────────

    /**
     * Called when a new (non-duplicate) ACK is received.
     */
    public synchronized void onAck() {
        switch (state) {
            case SLOW_START:
                // Exponential growth: +1 MSS per ACK
                cwnd += 1.0;
                if (cwnd >= ssthresh) {
                    state = State.CONGESTION_AVOIDANCE;
                }
                break;

            case CONGESTION_AVOIDANCE:
                // Additive increase: cwnd += 1/cwnd per ACK ≈ +1 MSS per RTT
                cwnd += 1.0 / cwnd;
                break;

            case FAST_RECOVERY:
                // Reno: on new ACK during fast recovery → deflate window
                // Go back to congestion avoidance
                cwnd = ssthresh;
                state = State.CONGESTION_AVOIDANCE;
                break;
        }
        recordHistory();
    }

    /**
     * Called when a retransmission timeout occurs.
     * Both Tahoe and Reno react identically:
     *   ssthresh = cwnd / 2
     *   cwnd = 1
     *   state → SLOW_START
     */
    public synchronized void onTimeout() {
        ssthresh = Math.max(cwnd / 2.0, 2.0);
        cwnd     = 1.0;
        state    = State.SLOW_START;
        recordHistory();
    }

    /**
     * Called when 3 duplicate ACKs are received (fast-retransmit trigger).
     *
     * Tahoe: same as timeout (ssthresh = cwnd/2, cwnd = 1, slow start)
     * Reno:  ssthresh = cwnd/2, cwnd = ssthresh + 3, fast recovery
     */
    public synchronized void onTripleDupAck() {
        ssthresh = Math.max(cwnd / 2.0, 2.0);

        if (mode == Mode.TAHOE) {
            // Tahoe treats triple dup-ACK like a timeout
            cwnd  = 1.0;
            state = State.SLOW_START;
        } else {
            // Reno enters fast recovery
            cwnd  = ssthresh + 3.0; // inflate by the 3 dup ACKs
            state = State.FAST_RECOVERY;
        }
        recordHistory();
    }

    // ── Window computation ───────────────────────────────────────────

    /**
     * Return the effective sending window: min(cwnd, receiverWindow).
     * At least 1 packet is always allowed.
     *
     * @param receiverWindow the receiver's advertised window size
     * @return number of packets the sender may have in flight
     */
    public synchronized int getEffectiveWindow(int receiverWindow) {
        int w = (int) Math.min(cwnd, receiverWindow);
        return Math.max(w, 1);
    }

    // ── History ──────────────────────────────────────────────────────

    private void recordHistory() {
        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        cwndHistory.add(new double[]{ elapsed, cwnd });
    }

    /**
     * Return a snapshot of the cwnd history for plotting.
     * Each entry is a double[2]: {elapsedSeconds, cwnd}.
     */
    public synchronized List<double[]> getCwndHistory() {
        return Collections.unmodifiableList(new ArrayList<>(cwndHistory));
    }

    // ── Getters ──────────────────────────────────────────────────────

    public synchronized double getCwnd()      { return cwnd; }
    public synchronized double getSsthresh()  { return ssthresh; }
    public synchronized State  getState()     { return state; }
    public Mode                getMode()      { return mode; }

    @Override
    public String toString() {
        return String.format("CC[%s %s cwnd=%.2f ssthresh=%.2f]",
                mode, state, cwnd, ssthresh);
    }
}
