package meshtalk.bench;

/**
 * Encapsulates the benchmark metrics for a server mode or protocol test run.
 */
public class BenchmarkResult {
    public String mode;
    public int numClients;
    public int messagesPerClient;
    public int totalMessages;
    public double avgResponseTimeMs;
    public long p95ResponseTimeMs;
    public double throughputMsgPerSec;
    public double connectionTimeMs;
    public int threadCount;
    public double durationSec;
    public String notes;

    @Override
    public String toString() {
        return String.format("[%s] clients=%d, avgResp=%.2fms, p95=%dms, tput=%.1f msg/s, threads=%d, notes=%s",
                mode, numClients, avgResponseTimeMs, p95ResponseTimeMs, throughputMsgPerSec, threadCount, notes);
    }
}
