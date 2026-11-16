package meshtalk.nettools;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.MeshLogger;

import java.util.Arrays;

/**
 * Build and parse a standard IPv4 header (20 bytes, no options).
 *
 * <p>IPv4 header layout (RFC 791):
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |Version|  IHL  |Type of Service|          Total Length         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         Identification        |Flags|      Fragment Offset    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Time to Live |    Protocol   |         Header Checksum       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       Source Address                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    Destination Address                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * @author MeshTalk Team
 */
public class Ipv4Header {

    private static final MeshLogger log = new MeshLogger("Ipv4Header");

    /** Header size in bytes (always 20, we don't support options). */
    public static final int HEADER_SIZE = 20;

    // ────────────────────────── Fields ──────────────────────────────────────────

    /** IP version (always 4). */
    public final int version;
    /** Internet Header Length in 32-bit words (always 5 for 20-byte header). */
    public final int ihl;
    /** Type of Service / DSCP + ECN (8 bits). */
    public final int tos;
    /** Total length of the IP datagram (header + payload) in bytes. */
    public final int totalLength;
    /** Identification field (16 bits) — used for fragment reassembly. */
    public final int identification;
    /**
     * Flags field (3 bits):
     *   bit 0: Reserved (must be 0)
     *   bit 1: Don't Fragment (DF)
     *   bit 2: More Fragments (MF)
     */
    public final int flags;
    /** Fragment offset in 8-byte units (13 bits). */
    public final int fragmentOffset;
    /** Time to Live (8 bits). */
    public final int ttl;
    /** Protocol number (8 bits): 6=TCP, 17=UDP, 1=ICMP, etc. */
    public final int protocol;
    /** Header checksum (16 bits). */
    public int headerChecksum;
    /** Source IP address as a 32-bit integer. */
    public final int sourceIp;
    /** Destination IP address as a 32-bit integer. */
    public final int destIp;

    // ────────────────────────── Constructors ────────────────────────────────────

    /**
     * Create an IPv4 header with the given field values.
     * The checksum is computed automatically.
     *
     * @param tos            Type of Service (0-255)
     * @param totalLength    total datagram length in bytes (20 to 65535)
     * @param identification identification value (0-65535)
     * @param flags          3-bit flags (0-7): bit1=DF, bit2=MF
     * @param fragmentOffset fragment offset in 8-byte units (0-8191)
     * @param ttl            Time to Live (0-255)
     * @param protocol       protocol number (0-255)
     * @param sourceIp       source IP as dotted decimal string
     * @param destIp         destination IP as dotted decimal string
     */
    public Ipv4Header(int tos, int totalLength, int identification,
                      int flags, int fragmentOffset, int ttl, int protocol,
                      String sourceIp, String destIp) {
        this(4, 5, tos, totalLength, identification, flags, fragmentOffset,
             ttl, protocol, 0,
             SubnetCalculator.ipToInt(sourceIp),
             SubnetCalculator.ipToInt(destIp));
        // Compute the checksum now that all fields are set
        this.headerChecksum = computeChecksum();
    }

    /**
     * Internal constructor with all raw integer fields.
     * Used by the parse() method and the public constructor.
     */
    Ipv4Header(int version, int ihl, int tos, int totalLength,
               int identification, int flags, int fragmentOffset,
               int ttl, int protocol, int headerChecksum,
               int sourceIp, int destIp) {
        this.version = version;
        this.ihl = ihl;
        this.tos = tos;
        this.totalLength = totalLength;
        this.identification = identification;
        this.flags = flags;
        this.fragmentOffset = fragmentOffset;
        this.ttl = ttl;
        this.protocol = protocol;
        this.headerChecksum = headerChecksum;
        this.sourceIp = sourceIp;
        this.destIp = destIp;
    }

    // ────────────────────────── Parsing ─────────────────────────────────────────

    /**
     * Parse an IPv4 header from a 20-byte (or larger) byte array.
     *
     * @param data byte array containing the IP header at the start
     * @return the parsed Ipv4Header
     * @throws IllegalArgumentException if the data is too short or the header is invalid
     */
    public static Ipv4Header parse(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Data too short for IPv4 header: need at least 20 bytes, got "
                    + (data == null ? 0 : data.length));
        }

        // Byte 0: version (4 bits) + IHL (4 bits)
        int versionIhl = data[0] & 0xFF;
        int version = (versionIhl >> 4) & 0x0F;
        int ihl = versionIhl & 0x0F;

        if (version != 4) {
            throw new IllegalArgumentException("Not an IPv4 header: version=" + version);
        }

        // Byte 1: TOS
        int tos = data[1] & 0xFF;

        // Bytes 2-3: Total length (big-endian)
        int totalLength = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

        // Bytes 4-5: Identification
        int identification = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);

        // Bytes 6-7: Flags (3 bits) + Fragment Offset (13 bits)
        int flagsAndOffset = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        int flags = (flagsAndOffset >> 13) & 0x07;
        int fragmentOffset = flagsAndOffset & 0x1FFF;

        // Byte 8: TTL
        int ttl = data[8] & 0xFF;

        // Byte 9: Protocol
        int protocol = data[9] & 0xFF;

        // Bytes 10-11: Header Checksum
        int headerChecksum = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);

        // Bytes 12-15: Source IP
        int sourceIp = ((data[12] & 0xFF) << 24) | ((data[13] & 0xFF) << 16)
                      | ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);

        // Bytes 16-19: Destination IP
        int destIp = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16)
                    | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);

        log.debug("Parsed IPv4 header: %s → %s, proto=%d, len=%d",
                SubnetCalculator.intToIp(sourceIp), SubnetCalculator.intToIp(destIp),
                protocol, totalLength);

        return new Ipv4Header(version, ihl, tos, totalLength, identification,
                              flags, fragmentOffset, ttl, protocol, headerChecksum,
                              sourceIp, destIp);
    }

    // ────────────────────────── Serialization ───────────────────────────────────

    /**
     * Serialize this header into a 20-byte array (big-endian).
     *
     * @return the 20-byte header
     */
    public byte[] toBytes() {
        byte[] header = new byte[HEADER_SIZE];

        // Byte 0: version (4) + IHL (5) → 0x45
        header[0] = (byte) (((version & 0x0F) << 4) | (ihl & 0x0F));

        // Byte 1: TOS
        header[1] = (byte) (tos & 0xFF);

        // Bytes 2-3: Total Length
        header[2] = (byte) ((totalLength >> 8) & 0xFF);
        header[3] = (byte) (totalLength & 0xFF);

        // Bytes 4-5: Identification
        header[4] = (byte) ((identification >> 8) & 0xFF);
        header[5] = (byte) (identification & 0xFF);

        // Bytes 6-7: Flags (3 bits) + Fragment Offset (13 bits)
        int flagsAndOffset = ((flags & 0x07) << 13) | (fragmentOffset & 0x1FFF);
        header[6] = (byte) ((flagsAndOffset >> 8) & 0xFF);
        header[7] = (byte) (flagsAndOffset & 0xFF);

        // Byte 8: TTL
        header[8] = (byte) (ttl & 0xFF);

        // Byte 9: Protocol
        header[9] = (byte) (protocol & 0xFF);

        // Bytes 10-11: Header Checksum
        header[10] = (byte) ((headerChecksum >> 8) & 0xFF);
        header[11] = (byte) (headerChecksum & 0xFF);

        // Bytes 12-15: Source IP
        header[12] = (byte) ((sourceIp >> 24) & 0xFF);
        header[13] = (byte) ((sourceIp >> 16) & 0xFF);
        header[14] = (byte) ((sourceIp >> 8) & 0xFF);
        header[15] = (byte) (sourceIp & 0xFF);

        // Bytes 16-19: Destination IP
        header[16] = (byte) ((destIp >> 24) & 0xFF);
        header[17] = (byte) ((destIp >> 16) & 0xFF);
        header[18] = (byte) ((destIp >> 8) & 0xFF);
        header[19] = (byte) (destIp & 0xFF);

        return header;
    }

    // ────────────────────────── Checksum ────────────────────────────────────────

    /**
     * Compute the IPv4 header checksum using the Internet checksum algorithm.
     *
     * <p>The checksum field is treated as zero during computation, then the
     * one's-complement sum of all 16-bit words is taken, and the result is
     * the one's complement of that sum.</p>
     *
     * @return the 16-bit checksum value
     */
    public int computeChecksum() {
        byte[] header = toBytes();
        // Zero out the checksum field (bytes 10-11) for computation
        header[10] = 0;
        header[11] = 0;
        return ChecksumUtil.internetChecksum(header);
    }

    /**
     * Verify that the header checksum is correct.
     *
     * <p>When you compute the Internet checksum over the entire header
     * (including the checksum field), a correct header yields 0xFFFF.</p>
     *
     * @return true if the checksum is valid
     */
    public boolean verifyChecksum() {
        byte[] header = toBytes();
        return ChecksumUtil.verifyInternetChecksum(header, 0, header.length);
    }

    // ────────────────────────── Display ─────────────────────────────────────────

    /**
     * Return the source IP as a dotted-decimal string.
     */
    public String getSourceIpString() {
        return SubnetCalculator.intToIp(sourceIp);
    }

    /**
     * Return the destination IP as a dotted-decimal string.
     */
    public String getDestIpString() {
        return SubnetCalculator.intToIp(destIp);
    }

    /**
     * Returns a detailed, field-by-field table showing decimal, hex, and binary
     * for each IPv4 header field.
     *
     * @return a multi-line formatted string
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║                          IPv4 HEADER DETAILS                            ║\n");
        sb.append("╠═══════════════════╦══════════╦════════════╦══════════════════════════════╣\n");
        sb.append("║ Field             ║ Decimal  ║ Hex        ║ Binary                       ║\n");
        sb.append("╠═══════════════════╬══════════╬════════════╬══════════════════════════════╣\n");

        appendField(sb, "Version",         version,        4);
        appendField(sb, "IHL",             ihl,            4);
        appendField(sb, "TOS/DSCP",        tos,            8);
        appendField(sb, "Total Length",    totalLength,    16);
        appendField(sb, "Identification",  identification, 16);
        appendField(sb, "Flags",           flags,          3);
        appendField(sb, "Fragment Offset", fragmentOffset, 13);
        appendField(sb, "TTL",             ttl,            8);
        appendField(sb, "Protocol",        protocol,       8);
        appendField(sb, "Header Checksum", headerChecksum, 16);
        appendField(sb, "Source IP",       sourceIp,       32);
        appendField(sb, "Dest IP",         destIp,         32);

        sb.append("╠═══════════════════╩══════════╩════════════╩══════════════════════════════╣\n");
        sb.append(String.format("║  Source      : %-58s║\n", getSourceIpString()));
        sb.append(String.format("║  Destination : %-58s║\n", getDestIpString()));
        sb.append(String.format("║  Checksum OK : %-58s║\n", verifyChecksum() ? "YES ✓" : "NO ✗"));
        sb.append("╚══════════════════════════════════════════════════════════════════════════╝");
        return sb.toString();
    }

    /**
     * Append one row to the detailed table.
     */
    private void appendField(StringBuilder sb, String name, int value, int bits) {
        long unsigned = value & 0xFFFFFFFFL;
        String binary = String.format("%" + bits + "s",
                Long.toBinaryString(unsigned)).replace(' ', '0');
        // Truncate binary to the specified bit width
        if (binary.length() > bits) {
            binary = binary.substring(binary.length() - bits);
        }
        sb.append(String.format("║ %-17s ║ %-8d ║ 0x%-8s ║ %-28s ║\n",
                name, unsigned, Long.toHexString(unsigned).toUpperCase(), binary));
    }

    @Override
    public String toString() {
        return String.format("IPv4[%s → %s, proto=%d, len=%d, id=%d, ttl=%d, flags=%d, offset=%d]",
                getSourceIpString(), getDestIpString(), protocol, totalLength,
                identification, ttl, flags, fragmentOffset);
    }
}
