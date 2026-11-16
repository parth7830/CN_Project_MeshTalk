package meshtalk.server;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live server metrics exposed to the UI and UDP status queries.
 *
 * All counters use atomic types so they can be safely updated from
 * multiple handler threads without explicit synchronisation.
 *
 * The toJson() method produces a simple JSON string that the
 * JavaFX dashboard or any HTTP client can parse.
 */
public class ServerMetrics {

    // ── Counters ───────────────────────────────────────────────────────
    private final AtomicInteger connectedClients = new AtomicInteger(0);
    private final AtomicLong    totalMessages    = new AtomicLong(0);
    private final AtomicLong    totalBytes       = new AtomicLong(0);
    private final AtomicInteger activeThreads    = new AtomicInteger(0);
    private final AtomicInteger queueLength      = new AtomicInteger(0);

    // ── Start time (set once when the server boots) ────────────────────
    private final long startTime = System.currentTimeMillis();

    // ── Getters ────────────────────────────────────────────────────────

    public int  getConnectedClients() { return connectedClients.get(); }
    public long getTotalMessages()    { return totalMessages.get(); }
    public long getTotalBytes()       { return totalBytes.get(); }
    public int  getActiveThreads()    { return activeThreads.get(); }
    public int  getQueueLength()      { return queueLength.get(); }
    public long getStartTime()        { return startTime; }

    // ── Mutators (called from handler threads) ─────────────────────────

    public void clientConnected()     { connectedClients.incrementAndGet(); }
    public void clientDisconnected()  { connectedClients.decrementAndGet(); }
    public void messageReceived()     { totalMessages.incrementAndGet(); }
    public void bytesTransferred(long n) { totalBytes.addAndGet(n); }
    public void setActiveThreads(int n)  { activeThreads.set(n); }
    public void setQueueLength(int n)    { queueLength.set(n); }

    // ── Derived metrics ────────────────────────────────────────────────

    /**
     * Server uptime in seconds.
     */
    public double getUptime() {
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }

    /**
     * Average messages per second since the server started.
     * Returns 0 if the server just started (avoid division by zero).
     */
    public double getMessagesPerSecond() {
        double uptime = getUptime();
        return (uptime > 0) ? totalMessages.get() / uptime : 0.0;
    }

    // ── JSON serialisation (no external libs) ──────────────────────────

    /**
     * Produce a JSON string with all current metrics.
     * This is sent over UDP for status queries and displayed in the UI.
     */
    public String toJson() {
        return String.format(
                "{\"connectedClients\":%d,"
              + "\"totalMessages\":%d,"
              + "\"totalBytes\":%d,"
              + "\"activeThreads\":%d,"
              + "\"queueLength\":%d,"
              + "\"uptimeSeconds\":%.1f,"
              + "\"messagesPerSecond\":%.2f}",
                connectedClients.get(),
                totalMessages.get(),
                totalBytes.get(),
                activeThreads.get(),
                queueLength.get(),
                getUptime(),
                getMessagesPerSecond()
        );
    }

    @Override
    public String toString() {
        return toJson();
    }
}
