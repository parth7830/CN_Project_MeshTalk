package meshtalk.nettools;

import meshtalk.common.MeshLogger;
import java.util.*;

/**
 * Simulates Address Resolution Protocol (ARP) cache and broadcast/unicast exchange.
 */
public class ArpSimulator {

    private static final MeshLogger log = new MeshLogger("ArpSimulator");

    public static class ArpEntry {
        public final String ip;
        public final String mac;
        public long expirationTime;

        public ArpEntry(String ip, String mac, long ttlMs) {
            this.ip = ip;
            this.mac = mac;
            this.expirationTime = System.currentTimeMillis() + ttlMs;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }

        @Override
        public String toString() {
            long remainingSec = Math.max(0, (expirationTime - System.currentTimeMillis()) / 1000);
            return String.format("IP: %-15s -> MAC: %-17s (TTL: %ds)", ip, mac, remainingSec);
        }
    }

    private final String localIp;
    private final String localMac;
    private final Map<String, ArpEntry> cache = new LinkedHashMap<>();

    public ArpSimulator(String localIp, String localMac) {
        this.localIp = localIp;
        this.localMac = localMac;
    }

    /**
     * Resolves IP to MAC. Returns cached MAC or performs simulated ARP request/response.
     */
    public synchronized String resolve(String targetIp, Map<String, String> networkHosts) {
        cleanExpired();
        ArpEntry entry = cache.get(targetIp);
        if (entry != null) {
            log.info("[ARP HIT] %s resolved to %s from cache", targetIp, entry.mac);
            return entry.mac;
        }

        log.info("[ARP MISS] Broadcasting: Who has %s? Tell %s (%s)", targetIp, localIp, localMac);
        String targetMac = networkHosts.get(targetIp);
        if (targetMac != null) {
            log.info("[ARP REPLY] %s says: %s is at %s", targetIp, targetIp, targetMac);
            cache.put(targetIp, new ArpEntry(targetIp, targetMac, 60_000));
            return targetMac;
        }

        log.warn("[ARP FAILED] Host unreachable: %s", targetIp);
        return null;
    }

    private void cleanExpired() {
        cache.values().removeIf(ArpEntry::isExpired);
    }

    public synchronized List<ArpEntry> getCache() {
        cleanExpired();
        return new ArrayList<>(cache.values());
    }
}
