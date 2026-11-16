package meshtalk.nettools;

import meshtalk.common.MeshLogger;
import java.util.*;

/**
 * Simulates a Network Address Translation (NAT) router table.
 * Translates private IP:port pairs to public IP:port pairs.
 */
public class NatSimulator {

    private static final MeshLogger log = new MeshLogger("NatSimulator");

    public static class NatMapping {
        public final String internalIp;
        public final int internalPort;
        public final String externalIp;
        public final int externalPort;
        public final String publicGatewayIp;
        public final int translatedPort;
        public long lastActiveTime;

        public NatMapping(String internalIp, int internalPort, String externalIp,
                          int externalPort, String publicGatewayIp, int translatedPort) {
            this.internalIp = internalIp;
            this.internalPort = internalPort;
            this.externalIp = externalIp;
            this.externalPort = externalPort;
            this.publicGatewayIp = publicGatewayIp;
            this.translatedPort = translatedPort;
            this.lastActiveTime = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("%s:%d <===> %s:%d (NAT Public)  ----->  %s:%d (Remote)",
                    internalIp, internalPort, publicGatewayIp, translatedPort, externalIp, externalPort);
        }
    }

    private final String publicGatewayIp;
    private int nextPort = 40000;
    private final List<NatMapping> table = new ArrayList<>();

    public NatSimulator(String publicGatewayIp) {
        this.publicGatewayIp = publicGatewayIp;
    }

    /**
     * Outbound packet: private host -> remote server.
     */
    public synchronized NatMapping translateOutbound(String internalIp, int internalPort, String externalIp, int externalPort) {
        for (NatMapping m : table) {
            if (m.internalIp.equals(internalIp) && m.internalPort == internalPort &&
                m.externalIp.equals(externalIp) && m.externalPort == externalPort) {
                m.lastActiveTime = System.currentTimeMillis();
                return m;
            }
        }
        int allocatedPort = nextPort++;
        NatMapping mapping = new NatMapping(internalIp, internalPort, externalIp, externalPort, publicGatewayIp, allocatedPort);
        table.add(mapping);
        return mapping;
    }

    /**
     * Inbound packet: remote server -> NAT public port.
     */
    public synchronized NatMapping translateInbound(int translatedPort, String externalIp, int externalPort) {
        for (NatMapping m : table) {
            if (m.translatedPort == translatedPort && m.externalIp.equals(externalIp) && m.externalPort == externalPort) {
                m.lastActiveTime = System.currentTimeMillis();
                return m;
            }
        }
        return null; // Dropped / unsolicited packet rejected by stateful NAT
    }

    public synchronized List<NatMapping> getTable() {
        return new ArrayList<>(table);
    }
}
