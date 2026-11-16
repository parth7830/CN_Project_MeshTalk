package meshtalk.nettools;

import meshtalk.common.MeshLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Variable Length Subnet Mask (VLSM) planner.
 *
 * <p>Allocates subnets from a parent network block based on required host counts.
 * Uses the VLSM algorithm: sort by required hosts (largest first), allocate each
 * one the smallest power-of-2 block that fits, then advance the base address.</p>
 *
 * <p>Also supports splitting a block into N equal subnets.</p>
 *
 * <p>Usage:
 * <pre>
 *   VlsmPlanner planner = new VlsmPlanner();
 *   List&lt;VlsmAllocation&gt; result = planner.plan("192.168.1.0/24",
 *       List.of(new HostRequirement("LAN-A", 50),
 *               new HostRequirement("LAN-B", 20)));
 *   result.forEach(System.out::println);
 * </pre>
 *
 * @author MeshTalk Team
 */
public class VlsmPlanner {

    private static final MeshLogger log = new MeshLogger("VlsmPlanner");

    // ────────────────────────── Inner Classes ───────────────────────────────────

    /**
     * Represents a host-count requirement for a single subnet.
     */
    public static class HostRequirement {
        /** A label or name for this subnet (e.g. "Sales", "LAN-A"). */
        public final String name;
        /** The minimum number of usable hosts required. */
        public final int requiredHosts;

        public HostRequirement(String name, int requiredHosts) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Subnet name cannot be null or empty");
            }
            if (requiredHosts < 1) {
                throw new IllegalArgumentException(
                        "Required hosts must be >= 1, got: " + requiredHosts);
            }
            this.name = name;
            this.requiredHosts = requiredHosts;
        }

        @Override
        public String toString() {
            return name + " (needs " + requiredHosts + " hosts)";
        }
    }

    /**
     * Represents a single VLSM allocation — one subnet carved from the parent block.
     */
    public static class VlsmAllocation {
        /** The label/name of this subnet. */
        public final String name;
        /** Network address in dotted decimal. */
        public final String networkAddress;
        /** CIDR prefix length (e.g. 26). */
        public final int prefixLength;
        /** Subnet mask in dotted decimal. */
        public final String subnetMask;
        /** First usable host address. */
        public final String firstHost;
        /** Last usable host address. */
        public final String lastHost;
        /** Broadcast address. */
        public final String broadcastAddress;
        /** Number of usable host addresses. */
        public final int usableHosts;
        /** The number of hosts that were requested. */
        public final int requestedHosts;

        VlsmAllocation(String name, String networkAddress, int prefixLength,
                       String subnetMask, String firstHost, String lastHost,
                       String broadcastAddress, int usableHosts, int requestedHosts) {
            this.name = name;
            this.networkAddress = networkAddress;
            this.prefixLength = prefixLength;
            this.subnetMask = subnetMask;
            this.firstHost = firstHost;
            this.lastHost = lastHost;
            this.broadcastAddress = broadcastAddress;
            this.usableHosts = usableHosts;
            this.requestedHosts = requestedHosts;
        }

        @Override
        public String toString() {
            return String.format(
                    "%-15s  %-18s  /%d  mask=%-15s  hosts=%-5d (needed %d)  range=%s – %s  bcast=%s",
                    name, networkAddress, prefixLength, subnetMask,
                    usableHosts, requestedHosts, firstHost, lastHost, broadcastAddress);
        }
    }

    // ────────────────────────── VLSM Planning ──────────────────────────────────

    /**
     * Plan VLSM subnets from a parent block for a list of host requirements.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Sort requirements by number of hosts needed (largest first).</li>
     *   <li>For each requirement, find the smallest prefix (largest block) where
     *       usable hosts &gt;= required hosts.</li>
     *   <li>Allocate that block starting at the current base address.</li>
     *   <li>Advance the base address by the block size.</li>
     * </ol>
     *
     * @param parentNetwork the parent network in CIDR notation, e.g. "192.168.1.0/24"
     * @param requirements  list of host requirements per subnet
     * @return list of VlsmAllocation objects
     * @throws IllegalArgumentException if requirements exceed the parent block size
     */
    public List<VlsmAllocation> plan(String parentNetwork, List<HostRequirement> requirements) {
        if (parentNetwork == null || !parentNetwork.contains("/")) {
            throw new IllegalArgumentException(
                    "Parent network must be in CIDR notation, e.g. '192.168.1.0/24'");
        }
        if (requirements == null || requirements.isEmpty()) {
            throw new IllegalArgumentException("Requirements list cannot be null or empty");
        }

        // Parse the parent network
        String[] parts = parentNetwork.split("/");
        String parentIp = parts[0].trim();
        int parentPrefix = Integer.parseInt(parts[1].trim());
        int parentNetworkInt = SubnetCalculator.ipToInt(parentIp);
        int parentMaskInt = (parentPrefix == 0) ? 0 : (0xFFFFFFFF << (32 - parentPrefix));
        parentNetworkInt = parentNetworkInt & parentMaskInt; // Normalize to network address

        long parentSize = 1L << (32 - parentPrefix); // Total addresses in parent block
        long parentEndAddress = (parentNetworkInt & 0xFFFFFFFFL) + parentSize;

        // Sort requirements: largest first (greedy approach)
        List<HostRequirement> sorted = new ArrayList<>(requirements);
        sorted.sort(Comparator.comparingInt((HostRequirement r) -> r.requiredHosts).reversed());

        List<VlsmAllocation> allocations = new ArrayList<>();
        long currentBase = parentNetworkInt & 0xFFFFFFFFL; // unsigned base address

        for (HostRequirement req : sorted) {
            // Find the smallest block size that can hold req.requiredHosts
            // Usable hosts = 2^(32 - prefix) - 2, so we need 2^(32-prefix) >= requiredHosts + 2
            int hostBits = ceilLog2(req.requiredHosts + 2);
            if (hostBits < 2) hostBits = 2; // Minimum /30 for at least 2 hosts
            int subnetPrefix = 32 - hostBits;
            long blockSize = 1L << hostBits;
            int usableHosts = (int) (blockSize - 2);

            // Check we haven't run out of address space
            if (currentBase + blockSize > parentEndAddress) {
                throw new IllegalArgumentException(String.format(
                        "Cannot allocate subnet '%s' (needs %d hosts = /%d block of %d addresses). "
                      + "Only %d addresses remaining in parent %s.",
                        req.name, req.requiredHosts, subnetPrefix, blockSize,
                        parentEndAddress - currentBase, parentNetwork));
            }

            int netInt = (int) currentBase;
            int bcastInt = (int) (currentBase + blockSize - 1);
            String maskStr = SubnetCalculator.prefixToMask(subnetPrefix);

            allocations.add(new VlsmAllocation(
                    req.name,
                    SubnetCalculator.intToIp(netInt),
                    subnetPrefix,
                    maskStr,
                    SubnetCalculator.intToIp(netInt + 1),
                    SubnetCalculator.intToIp(bcastInt - 1),
                    SubnetCalculator.intToIp(bcastInt),
                    usableHosts,
                    req.requiredHosts
            ));

            log.info("Allocated %-15s → %s/%d (%d usable hosts)",
                    req.name, SubnetCalculator.intToIp(netInt), subnetPrefix, usableHosts);

            currentBase += blockSize;
        }

        return allocations;
    }

    // ────────────────────────── Equal-Split ─────────────────────────────────────

    /**
     * Split a network block into N equal-sized subnets.
     *
     * <p>N must be a power of 2 (1, 2, 4, 8, 16, …). Each resulting subnet has
     * prefix = parent_prefix + log2(N).</p>
     *
     * @param network the parent network in CIDR notation
     * @param n       number of equal subnets to create (must be power of 2)
     * @return list of VlsmAllocation objects for each sub-subnet
     * @throws IllegalArgumentException if N is not a power of 2 or exceeds capacity
     */
    public List<VlsmAllocation> splitIntoN(String network, int n) {
        if (n <= 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException(
                    "N must be a positive power of 2, got: " + n);
        }
        if (network == null || !network.contains("/")) {
            throw new IllegalArgumentException(
                    "Network must be in CIDR notation, e.g. '192.168.1.0/24'");
        }

        String[] parts = network.split("/");
        String parentIp = parts[0].trim();
        int parentPrefix = Integer.parseInt(parts[1].trim());
        int parentNetworkInt = SubnetCalculator.ipToInt(parentIp);
        int parentMaskInt = (parentPrefix == 0) ? 0 : (0xFFFFFFFF << (32 - parentPrefix));
        parentNetworkInt = parentNetworkInt & parentMaskInt;

        // Each sub-subnet needs log2(n) extra bits
        int extraBits = Integer.numberOfTrailingZeros(n); // log2(n) since n is power of 2
        int subPrefix = parentPrefix + extraBits;
        if (subPrefix > 32) {
            throw new IllegalArgumentException(String.format(
                    "Cannot split /%d into %d subnets — would require /%d which exceeds /32",
                    parentPrefix, n, subPrefix));
        }

        long subBlockSize = 1L << (32 - subPrefix);
        long currentBase = parentNetworkInt & 0xFFFFFFFFL;

        List<VlsmAllocation> allocations = new ArrayList<>();
        String maskStr = SubnetCalculator.prefixToMask(subPrefix);

        for (int i = 0; i < n; i++) {
            int netInt = (int) currentBase;
            int bcastInt = (int) (currentBase + subBlockSize - 1);
            int usableHosts = (subPrefix <= 30) ? (int) (subBlockSize - 2) : (subPrefix == 31 ? 2 : 1);

            String firstHost, lastHost;
            if (subPrefix >= 31) {
                firstHost = SubnetCalculator.intToIp(netInt);
                lastHost = SubnetCalculator.intToIp(bcastInt);
            } else {
                firstHost = SubnetCalculator.intToIp(netInt + 1);
                lastHost = SubnetCalculator.intToIp(bcastInt - 1);
            }

            allocations.add(new VlsmAllocation(
                    "Subnet-" + (i + 1),
                    SubnetCalculator.intToIp(netInt),
                    subPrefix,
                    maskStr,
                    firstHost,
                    lastHost,
                    SubnetCalculator.intToIp(bcastInt),
                    usableHosts,
                    usableHosts // requested == provided for equal splits
            ));

            currentBase += subBlockSize;
        }

        log.info("Split %s into %d equal /%d subnets", network, n, subPrefix);
        return allocations;
    }

    // ────────────────────────── Display Helper ──────────────────────────────────

    /**
     * Format a list of allocations as a readable table.
     *
     * @param allocations the VLSM allocations to display
     * @return a formatted table string
     */
    public static String formatTable(List<VlsmAllocation> allocations) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-15s %-18s %-6s %-17s %-7s %-8s %-15s %-15s %-15s\n",
                "Name", "Network", "Prefix", "Mask", "Usable", "Needed",
                "First Host", "Last Host", "Broadcast"));
        sb.append("─".repeat(130)).append("\n");
        for (VlsmAllocation a : allocations) {
            sb.append(String.format("%-15s %-18s /%-5d %-17s %-7d %-8d %-15s %-15s %-15s\n",
                    a.name, a.networkAddress, a.prefixLength, a.subnetMask,
                    a.usableHosts, a.requestedHosts, a.firstHost, a.lastHost,
                    a.broadcastAddress));
        }
        return sb.toString();
    }

    // ────────────────────────── Utility ─────────────────────────────────────────

    /**
     * Compute the ceiling of log base 2 of a value.
     * For example: ceilLog2(5) = 3, ceilLog2(8) = 3, ceilLog2(9) = 4.
     */
    private int ceilLog2(int value) {
        if (value <= 1) return 0;
        // 32 - leading zeros gives floor(log2) + 1 for non-powers
        // But we need the ceiling: the smallest k such that 2^k >= value
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
