package meshtalk.rdt;

import meshtalk.common.ChecksumUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Custom UDP-layer packet for Reliable Data Transfer.
 *
 * Header layout (17 bytes total):
 * ┌──────────┬───────┬───────┬────────────┬────────┬──────────┬─────────┐
 * │ type (1) │seq (4)│ack(4)│ window (2) │ len(2) │ crc32(4) │ payload │
 * └──────────┴───────┴───────┴────────────┴────────┴──────────┴─────────┘
 *
 * The checksum covers everything EXCEPT the checksum field itself:
 *   CRC32(type + seqNo + ackNo + windowSize + length + payload)
 */
public class RdtPacket {

    // ── Header size constant ─────────────────────────────────────────
    public static final int HEADER_SIZE = 17; // 1+4+4+2+2+4

    // ── Packet type codes ────────────────────────────────────────────
    public enum Type {
        DATA     ((byte) 1),
        ACK      ((byte) 2),
        SYN      ((byte) 3),
        SYN_ACK  ((byte) 4),
        FIN      ((byte) 5),
        FIN_ACK  ((byte) 6),
        NACK     ((byte) 7);

        private final byte code;

        Type(byte code) {
            this.code = code;
        }

        public byte getCode() {
            return code;
        }

        /** Look up a Type by its wire byte. */
        public static Type fromCode(byte code) {
            for (Type t : values()) {
                if (t.code == code) return t;
            }
            throw new IllegalArgumentException("Unknown packet type code: " + code);
        }
    }

    // ── Fields ───────────────────────────────────────────────────────
    private Type type;
    private int seqNo;
    private int ackNo;
    private int windowSize;   // receiver-advertised window (unsigned 16-bit)
    private int length;       // payload length             (unsigned 16-bit)
    private int checksum;     // CRC32 truncated to 32 bits
    private byte[] payload;

    // ── Constructors ─────────────────────────────────────────────────

    /** Full constructor (used internally and by fromBytes). */
    public RdtPacket(Type type, int seqNo, int ackNo,
                     int windowSize, byte[] payload) {
        this.type = type;
        this.seqNo = seqNo;
        this.ackNo = ackNo;
        this.windowSize = windowSize & 0xFFFF;
        this.payload = (payload != null) ? payload : new byte[0];
        this.length = this.payload.length;
        computeChecksum();
    }

    /** Private no-arg constructor for deserialization. */
    private RdtPacket() {}

    // ── Factory methods ──────────────────────────────────────────────

    /** Create a DATA packet carrying payload at the given sequence number. */
    public static RdtPacket data(int seqNo, byte[] payload) {
        return new RdtPacket(Type.DATA, seqNo, 0, 0, payload);
    }

    /** Create an ACK packet with the given acknowledgement number and window. */
    public static RdtPacket ack(int ackNo, int windowSize) {
        return new RdtPacket(Type.ACK, 0, ackNo, windowSize, null);
    }

    /** Create a SYN packet (connection setup request). */
    public static RdtPacket syn() {
        return new RdtPacket(Type.SYN, 0, 0, 0, null);
    }

    /** Create a SYN_ACK packet (connection setup acknowledgement). */
    public static RdtPacket synAck() {
        return new RdtPacket(Type.SYN_ACK, 0, 0, 0, null);
    }

    /** Create a FIN packet (teardown request). Payload carries the SHA-256 hash. */
    public static RdtPacket fin() {
        return new RdtPacket(Type.FIN, 0, 0, 0, null);
    }

    /** Create a FIN packet with a SHA-256 hash payload for verification. */
    public static RdtPacket fin(byte[] sha256Payload) {
        return new RdtPacket(Type.FIN, 0, 0, 0, sha256Payload);
    }

    /** Create a FIN_ACK packet (teardown acknowledgement). */
    public static RdtPacket finAck() {
        return new RdtPacket(Type.FIN_ACK, 0, 0, 0, null);
    }

    /** Create a NACK (negative acknowledgement). */
    public static RdtPacket nack(int seqNo) {
        return new RdtPacket(Type.NACK, 0, seqNo, 0, null);
    }

    // ── Checksum ─────────────────────────────────────────────────────

    /**
     * Compute CRC32 over all header fields (except the checksum field)
     * plus the payload, then store the result.
     */
    public void computeChecksum() {
        byte[] raw = checksumInput();
        this.checksum = (int) ChecksumUtil.crc32(raw);
    }

    /**
     * Verify that the stored checksum matches a freshly computed one.
     *
     * @return true if the packet is intact
     */
    public boolean verifyChecksum() {
        byte[] raw = checksumInput();
        int computed = (int) ChecksumUtil.crc32(raw);
        return computed == this.checksum;
    }

    /**
     * Build the byte array that goes into the CRC32:
     *   type(1) + seqNo(4) + ackNo(4) + windowSize(2) + length(2) + payload
     */
    private byte[] checksumInput() {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 4 + 2 + 2 + payload.length);
        buf.put(type.getCode());
        buf.putInt(seqNo);
        buf.putInt(ackNo);
        buf.putShort((short) windowSize);
        buf.putShort((short) length);
        buf.put(payload);
        return buf.array();
    }

    // ── Serialization ────────────────────────────────────────────────

    /**
     * Serialize the entire packet (header + payload) to a byte array
     * suitable for sending over a DatagramSocket.
     */
    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buf.put(type.getCode());          // 1 byte
        buf.putInt(seqNo);                // 4 bytes
        buf.putInt(ackNo);                // 4 bytes
        buf.putShort((short) windowSize); // 2 bytes
        buf.putShort((short) length);     // 2 bytes
        buf.putInt(checksum);             // 4 bytes
        buf.put(payload);                 // variable
        return buf.array();
    }

    /**
     * Deserialize a byte array back into an RdtPacket.
     *
     * @param data raw bytes received from the network
     * @return the reconstructed packet
     * @throws IllegalArgumentException if data is too short
     */
    public static RdtPacket fromBytes(byte[] data) {
        if (data.length < HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "Packet too short: " + data.length + " bytes (need >= " + HEADER_SIZE + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        RdtPacket pkt = new RdtPacket();
        pkt.type = Type.fromCode(buf.get());
        pkt.seqNo = buf.getInt();
        pkt.ackNo = buf.getInt();
        pkt.windowSize = buf.getShort() & 0xFFFF;
        pkt.length = buf.getShort() & 0xFFFF;
        pkt.checksum = buf.getInt();

        // Read exactly 'length' bytes of payload (or whatever remains)
        int payloadLen = Math.min(pkt.length, buf.remaining());
        pkt.payload = new byte[payloadLen];
        buf.get(pkt.payload);
        // Correct the length field to match what we actually read
        pkt.length = payloadLen;

        return pkt;
    }

    // ── Getters ──────────────────────────────────────────────────────

    public Type getType()       { return type; }
    public int getSeqNo()       { return seqNo; }
    public int getAckNo()       { return ackNo; }
    public int getWindowSize()  { return windowSize; }
    public int getLength()      { return length; }
    public int getChecksum()    { return checksum; }
    public byte[] getPayload()  { return payload; }

    // ── Setters (for testing / internal use) ─────────────────────────

    public void setSeqNo(int seqNo)           { this.seqNo = seqNo; }
    public void setAckNo(int ackNo)           { this.ackNo = ackNo; }
    public void setWindowSize(int windowSize) { this.windowSize = windowSize & 0xFFFF; }
    public void setChecksum(int checksum)     { this.checksum = checksum; }
    public void setPayload(byte[] payload) {
        this.payload = (payload != null) ? payload : new byte[0];
        this.length = this.payload.length;
    }

    // ── Debug ────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return String.format("RdtPacket[%s seq=%d ack=%d win=%d len=%d crc=0x%08X]",
                type, seqNo, ackNo, windowSize, length, checksum);
    }
}
