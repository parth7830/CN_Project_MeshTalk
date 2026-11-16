package meshtalk.nettools;

import meshtalk.common.MeshLogger;

/**
 * Full subnet calculator supporting both classful and CIDR notation.
 *
 * <p>Given an IP address and a subnet mask (or CIDR prefix length like /24),
 * this class computes network ID, broadcast address, usable host range,
 * wildcard mask, IP class, and private/public status.</p>
 *
 * <p>Usage:
 * <pre>
 *   SubnetCalculator calc = new SubnetCalculator();
 *   SubnetInfo info = calc.calculate("192.168.1.100/24");
 *   System.out.println(info);
 * </pre>
 *
 * @author MeshTalk Team
 */
public class SubnetCalculator {

    private static final MeshLogger log = new MeshLogger("SubnetCalc");

    // ────────────────────────── Inner Class: SubnetInfo ──────────────────────────

    /**
     * Holds all computed subnet information for a given IP/mask combination.
     */
    public static class SubnetInfo {
        /** The original IP address (dotted decimal). */
        public final String ipAddress;
        /** The subnet mask in dotted decimal form. */
        public final String subnetMask;
        /** The CIDR prefix length (0-32). */
        public final int prefixLength;
        /** The network ID (the first address in the subnet). */
        public final String networkAddress;
        /** The broadcast address (the last address in the subnet). */
        public final String broadcastAddress;
        /** The first usable host address (network + 1). */
        public final String firstUsableHost;
        /** The last usable host address (broadcast - 1). */
        public final String lastUsableHost;
        /** Number of usable host addresses (2^host_bits - 2, min 0). */
        public final long usableHosts;
        /** Total number of addresses in the subnet (2^host_bits). */
        public final long totalAddresses;
        /** The wildcard mask (inverse of subnet mask). */
        public final String wildcardMask;
        /** Binary representation of the IP address. */
        public final String ipBinary;
        /** Binary representation of the subnet mask. */
        public final String maskBinary;
        /** The classful IP class: A, B, C, D, or E. */
        public final char ipClass;
        /** Whether this IP is in a private (RFC 1918) address range. */
        public final boolean isPrivate;

        /**
         * Package-private constructor — only SubnetCalculator creates these.
         */
        SubnetInfo(String ipAddress, String subnetMask, int prefixLength,
                   String networkAddress, String broadcastAddress,
                   String firstUsableHost, String lastUsableHost,
                   long usableHosts, long totalAddresses,
                   String wildcardMask, String ipBinary, String maskBinary,
                   char ipClass, boolean isPrivate) {
            this.ipAddress = ipAddress;
            this.subnetMask = subnetMask;
            this.prefixLength = prefixLength;
            this.networkAddress = networkAddress;
            this.broadcastAddress = broadcastAddress;
            this.firstUsableHost = firstUsableHost;
            this.lastUsableHost = lastUsableHost;
            this.usableHosts = usableHosts;
            this.totalAddresses = totalAddresses;
            this.wildcardMask = wildcardMask;
            this.ipBinary = ipBinary;
            this.maskBinary = maskBinary;
            this.ipClass = ipClass;
            this.isPrivate = isPrivate;
        }

        /**
         * Returns a nicely formatted multi-line table of all subnet details.
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║                    SUBNET CALCULATION RESULT                ║\n");
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║  IP Address       : %-40s║\n", ipAddress));
            sb.append(String.format("║  Subnet Mask      : %-40s║\n", subnetMask + " (/" + prefixLength + ")"));
            sb.append(String.format("║  Wildcard Mask    : %-40s║\n", wildcardMask));
            sb.append(String.format("║  Network Address  : %-40s║\n", networkAddress));
            sb.append(String.format("║  Broadcast Address: %-40s║\n", broadcastAddress));
            sb.append(String.format("║  First Usable Host: %-40s║\n", firstUsableHost));
            sb.append(String.format("║  Last Usable Host : %-40s║\n", lastUsableHost));
            sb.append(String.format("║  Usable Hosts     : %-40d║\n", usableHosts));
            sb.append(String.format("║  Total Addresses  : %-40d║\n", totalAddresses));
            sb.append(String.format("║  IP Class         : %-40s║\n", ipClass));
            sb.append(String.format("║  Private/Public   : %-40s║\n", isPrivate ? "Private" : "Public"));
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║  IP Binary        : %-40s║\n", ipBinary));
            sb.append(String.format("║  Mask Binary      : %-40s║\n", maskBinary));
            sb.append("╚══════════════════════════════════════════════════════════════╝");
            return sb.toString();
        }
    }

    // ────────────────────────── Public API ──────────────────────────────────────

    /**
     * Calculate subnet info from CIDR notation like "192.168.1.100/24".
     *
     * @param ipWithPrefix IP address with prefix length, e.g. "192.168.1.0/24"
     * @return a SubnetInfo with all computed fields
     * @throws IllegalArgumentException if the input is invalid
     */
    public static SubnetInfo calculate(String ipWithPrefix) {
        if (ipWithPrefix == null || !ipWithPrefix.contains("/")) {
            throw new IllegalArgumentException(
                    "Expected CIDR notation like '192.168.1.0/24', got: " + ipWithPrefix);
        }
        String[] parts = ipWithPrefix.split("/", 2);
        String ip = parts[0].trim();
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid prefix length: " + parts[1]);
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException(
                    "Prefix length must be 0-32, got: " + prefix);
        }
        // Convert prefix length to a dotted-decimal mask
        String mask = prefixToMask(prefix);
        return calculate(ip, mask);
    }

    /**
     * Calculate subnet info from an IP address and a dotted-decimal subnet mask.
     *
     * @param ip   IP address in dotted decimal, e.g. "192.168.1.100"
     * @param mask subnet mask in dotted decimal, e.g. "255.255.255.0"
     * @return a SubnetInfo with all computed fields
     * @throws IllegalArgumentException if either input is invalid
     */
    public static SubnetInfo calculate(String ip, String mask) {
        validateIp(ip);
        validateMask(mask);

        // Parse the IP and mask into 32-bit integers for bit manipulation
        int ipInt = ipToInt(ip);
        int maskInt = ipToInt(mask);
        int prefixLength = Integer.bitCount(maskInt);

        // Compute network address: IP AND mask
        int networkInt = ipInt & maskInt;
        // Compute broadcast address: network OR (NOT mask)
        int wildcardInt = ~maskInt;
        int broadcastInt = networkInt | wildcardInt;

        // Compute usable host range
        // For /32 (single host) and /31 (point-to-point link), special handling
        long totalAddresses = (1L << (32 - prefixLength));
        long usableHosts;
        String firstHost;
        String lastHost;

        if (prefixLength == 32) {
            // /32 = single host, no usable hosts (it IS the host)
            usableHosts = 1;
            firstHost = intToIp(networkInt);
            lastHost = intToIp(networkInt);
        } else if (prefixLength == 31) {
            // /31 = point-to-point (RFC 3021), 2 usable addresses
            usableHosts = 2;
            firstHost = intToIp(networkInt);
            lastHost = intToIp(broadcastInt);
        } else {
            // Normal subnet: subtract network and broadcast addresses
            usableHosts = totalAddresses - 2;
            firstHost = intToIp(networkInt + 1);
            lastHost = intToIp(broadcastInt - 1);
        }

        // Binary representations (dotted, 8 bits per octet)
        String ipBinary = intToBinaryDotted(ipInt);
        String maskBinary = intToBinaryDotted(maskInt);

        // Classful IP class based on first octet
        int firstOctet = (ipInt >>> 24) & 0xFF;
        char ipClass = determineClass(firstOctet);

        // Private address check (RFC 1918)
        boolean isPrivate = isPrivateAddress(ipInt);

        log.info("Calculated subnet for %s/%d → network=%s, broadcast=%s, hosts=%d",
                ip, prefixLength, intToIp(networkInt), intToIp(broadcastInt), usableHosts);

        return new SubnetInfo(
                ip, mask, prefixLength,
                intToIp(networkInt), intToIp(broadcastInt),
                firstHost, lastHost,
                usableHosts, totalAddresses,
                intToIp(wildcardInt), ipBinary, maskBinary,
                ipClass, isPrivate
        );
    }

    // ────────────────────────── Validation ──────────────────────────────────────

    /**
     * Validate that a string is a valid dotted-decimal IPv4 address.
     *
     * @throws IllegalArgumentException if the IP is invalid
     */
    private static void validateIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            throw new IllegalArgumentException("IP address cannot be null or empty");
        }
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException(
                    "IP address must have exactly 4 octets, got: " + ip);
        }
        for (String octetStr : octets) {
            int octet;
            try {
                octet = Integer.parseInt(octetStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid octet '" + octetStr + "' in IP: " + ip);
            }
            if (octet < 0 || octet > 255) {
                throw new IllegalArgumentException(
                        "Octet out of range (0-255): " + octet + " in IP: " + ip);
            }
        }
    }

    /**
     * Validate that a string is a valid subnet mask (contiguous 1s followed by 0s).
     *
     * @throws IllegalArgumentException if the mask is invalid
     */
    private static void validateMask(String mask) {
        validateIp(mask); // First check it's a valid dotted-decimal string
        int maskInt = ipToInt(mask);
        // A valid mask must be contiguous 1s followed by contiguous 0s.
        // Invert it and add 1: should be a power of 2 (or 0 for /32).
        if (maskInt == 0) {
            return; // /0 mask is valid (matches everything)
        }
        // Check: ~mask + 1 should be a power of 2, OR mask is all 1s
        int inverted = ~maskInt;
        if (maskInt == 0xFFFFFFFF) {
            return; // /32 is valid
        }
        // inverted should be like 0x000000FF (all low bits set)
        // inverted + 1 should be a power of 2
        long check = (inverted & 0xFFFFFFFFL) + 1;
        if ((check & (check - 1)) != 0) {
            throw new IllegalArgumentException(
                    "Invalid subnet mask (non-contiguous): " + mask);
        }
    }

    // ────────────────────────── Conversion Helpers ──────────────────────────────

    /**
     * Convert a dotted-decimal IP string to a 32-bit integer.
     * Example: "192.168.1.0" → 0xC0A80100
     */
    static int ipToInt(String ip) {
        String[] octets = ip.split("\\.");
        return ((Integer.parseInt(octets[0]) & 0xFF) << 24)
             | ((Integer.parseInt(octets[1]) & 0xFF) << 16)
             | ((Integer.parseInt(octets[2]) & 0xFF) << 8)
             |  (Integer.parseInt(octets[3]) & 0xFF);
    }

    /**
     * Convert a 32-bit integer back to a dotted-decimal IP string.
     * Example: 0xC0A80100 → "192.168.1.0"
     */
    static String intToIp(int ip) {
        return ((ip >>> 24) & 0xFF) + "."
             + ((ip >>> 16) & 0xFF) + "."
             + ((ip >>> 8)  & 0xFF) + "."
             + ( ip         & 0xFF);
    }

    /**
     * Convert a prefix length (0-32) to a dotted-decimal subnet mask.
     * Example: 24 → "255.255.255.0"
     */
    static String prefixToMask(int prefix) {
        // Shift left to get the right number of leading 1s
        // For prefix=0, mask is 0; for prefix=32, mask is 0xFFFFFFFF
        int maskInt = (prefix == 0) ? 0 : (0xFFFFFFFF << (32 - prefix));
        return intToIp(maskInt);
    }

    /**
     * Convert a 32-bit integer to a dotted binary string.
     * Example: 0xC0A80100 → "11000000.10101000.00000001.00000000"
     */
    static String intToBinaryDotted(int value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i >= 0; i--) {
            int octet = (value >>> (i * 8)) & 0xFF;
            // Pad each octet to 8 bits
            String binary = String.format("%8s", Integer.toBinaryString(octet)).replace(' ', '0');
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(binary);
        }
        return sb.toString();
    }

    /**
     * Determine the classful IP class based on the first octet.
     *   A: 0-127, B: 128-191, C: 192-223, D: 224-239, E: 240-255
     */
    static char determineClass(int firstOctet) {
        if (firstOctet <= 127) return 'A';
        if (firstOctet <= 191) return 'B';
        if (firstOctet <= 223) return 'C';
        if (firstOctet <= 239) return 'D';
        return 'E';
    }

    /**
     * Check if an IP (as a 32-bit int) falls within RFC 1918 private ranges:
     *   10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
     */
    static boolean isPrivateAddress(int ipInt) {
        int firstOctet = (ipInt >>> 24) & 0xFF;
        int secondOctet = (ipInt >>> 16) & 0xFF;

        // 10.0.0.0/8 — first octet is 10
        if (firstOctet == 10) return true;

        // 172.16.0.0/12 — first octet 172, second octet 16-31
        if (firstOctet == 172 && secondOctet >= 16 && secondOctet <= 31) return true;

        // 192.168.0.0/16 — first octet 192, second octet 168
        if (firstOctet == 192 && secondOctet == 168) return true;

        return false;
    }
}
