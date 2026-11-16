package meshtalk.common;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/**
 * Checksum and hashing utilities for MeshTalk.
 *
 * Provides:
 *  - CRC32 for RDT packet integrity
 *  - SHA-256 for file transfer verification
 *  - Internet (IP) checksum for IPv4 header simulation
 */
public final class ChecksumUtil {

    private ChecksumUtil() {}

    // ── CRC32 ──────────────────────────────────────────────────────────

    /** Compute CRC32 over a byte array. Returns a 32-bit value. */
    public static long crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    public static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    // ── SHA-256 ────────────────────────────────────────────────────────

    /** Compute SHA-256 hash and return it as a lowercase hex string. */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Create a streaming SHA-256 digest. */
    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** Convert a digest result to a lowercase hex string. */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ── Internet Checksum (RFC 1071) ───────────────────────────────────

    /**
     * Compute the Internet (one's complement) checksum used in IPv4 headers.
     *
     * Algorithm:
     *  1. Sum all 16-bit words (big-endian).
     *  2. Add any carry bits back into the lower 16 bits.
     *  3. Take the one's complement.
     *
     * @param data   the byte array to checksum
     * @param offset start position
     * @param length number of bytes (if odd, pad with a zero byte)
     * @return the 16-bit checksum as an int (0x0000–0xFFFF)
     */
    public static int internetChecksum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        int end = offset + length;

        // Sum 16-bit words
        while (i + 1 < end) {
            int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += word;
            i += 2;
        }

        // If there's an odd byte left, pad it with a zero byte
        if (i < end) {
            sum += (data[i] & 0xFF) << 8;
        }

        // Fold 32-bit sum into 16 bits (add carry)
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        // One's complement
        return (~(int) sum) & 0xFFFF;
    }

    /** Convenience: checksum the entire array. */
    public static int internetChecksum(byte[] data) {
        return internetChecksum(data, 0, data.length);
    }

    /**
     * Verify an Internet checksum. When you include the checksum field
     * in the data and compute, the result should be 0x0000.
     */
    public static boolean verifyInternetChecksum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        int end = offset + length;

        while (i + 1 < end) {
            int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += word;
            i += 2;
        }
        if (i < end) {
            sum += (data[i] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        return ((int) sum & 0xFFFF) == 0xFFFF;
    }
}
