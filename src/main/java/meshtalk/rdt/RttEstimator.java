package meshtalk.rdt;

import meshtalk.common.Constants;

/**
 * TCP-style adaptive RTO (Retransmission Timeout) estimator.
 *
 * Uses the standard formulas from RFC 6298:
 *   EstimatedRTT = (1 − α) × EstimatedRTT + α × SampleRTT
 *   DevRTT       = (1 − β) × DevRTT       + β × |SampleRTT − EstimatedRTT|
 *   RTO          = EstimatedRTT + 4 × DevRTT
 *
 * Where α = 0.125 and β = 0.25 (from Constants).
 *
 * On timeout, RTO is doubled (exponential backoff / Karn's algorithm).
 * RTO is always clamped between MIN_RTO and MAX_RTO.
 */
public class RttEstimator {

    private double estimatedRtt;  // smoothed RTT (ms)
    private double devRtt;        // RTT deviation (ms)
    private long   rto;           // current retransmission timeout (ms)
    private boolean firstSample;  // true until the very first sample arrives

    public RttEstimator() {
        this.estimatedRtt = Constants.INITIAL_RTO;
        this.devRtt       = 0;
        this.rto          = Constants.INITIAL_RTO;
        this.firstSample  = true;
    }

    /**
     * Add a fresh RTT sample and update the estimator.
     *
     * On the very first sample we initialise directly:
     *   EstimatedRTT = SampleRTT
     *   DevRTT       = SampleRTT / 2
     * (per RFC 6298 §2.2)
     *
     * @param sampleRttMs measured round-trip time in milliseconds
     */
    public synchronized void addSample(long sampleRttMs) {
        if (firstSample) {
            estimatedRtt = sampleRttMs;
            devRtt       = sampleRttMs / 2.0;
            firstSample  = false;
        } else {
            // Standard EWMA update
            estimatedRtt = (1 - Constants.ALPHA) * estimatedRtt
                         + Constants.ALPHA * sampleRttMs;
            devRtt       = (1 - Constants.BETA) * devRtt
                         + Constants.BETA * Math.abs(sampleRttMs - estimatedRtt);
        }

        // Compute RTO and clamp
        rto = Math.round(estimatedRtt + 4.0 * devRtt);
        rto = Math.max(rto, Constants.MIN_RTO);
        rto = Math.min(rto, Constants.MAX_RTO);
    }

    /**
     * Called after a retransmission timeout — double the RTO
     * (exponential backoff per Karn's algorithm).
     */
    public synchronized void timeout() {
        rto = Math.min(rto * 2, Constants.MAX_RTO);
    }

    // ── Getters ──────────────────────────────────────────────────────

    public synchronized long getRto() {
        return rto;
    }

    public synchronized long getEstimatedRtt() {
        return Math.round(estimatedRtt);
    }

    public synchronized long getDevRtt() {
        return Math.round(devRtt);
    }

    @Override
    public String toString() {
        return String.format("RttEstimator[est=%d dev=%d rto=%d]",
                getEstimatedRtt(), getDevRtt(), getRto());
    }
}
