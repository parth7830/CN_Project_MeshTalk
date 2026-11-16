package meshtalk.common;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Length-prefixed frame I/O for the MeshTalk application-layer protocol.
 *
 * Wire format:
 *   [4 bytes: payload length N (big-endian int)]
 *   [1 byte : message type code]
 *   [N-1 bytes: payload]
 *
 * The 4-byte length field gives the number of bytes that follow, which
 * includes the 1-byte type and (N-1) bytes of payload.
 *
 * This class handles TCP's stream nature correctly:
 *   - Uses DataInputStream.readFully() to cope with partial reads.
 *   - Validates length to reject oversized or zero-length frames.
 *   - Thread-safe write via synchronized output stream access.
 */
public class Frame {

    private final Protocol type;
    private final byte[] payload;

    public Frame(Protocol type, byte[] payload) {
        this.type = type;
        this.payload = (payload != null) ? payload : new byte[0];
    }

    /** Create a frame with a UTF-8 text payload. */
    public Frame(Protocol type, String text) {
        this(type, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Create a frame with no payload. */
    public Frame(Protocol type) {
        this(type, new byte[0]);
    }

    public Protocol getType() { return type; }
    public byte[] getPayload() { return payload; }

    /** Decode payload as UTF-8 text. */
    public String getText() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** Get the total wire size: 4 (length) + 1 (type) + payload. */
    public int wireSize() {
        return 4 + 1 + payload.length;
    }

    @Override
    public String toString() {
        String preview = payload.length <= 80
                ? getText()
                : getText().substring(0, 80) + "...";
        return String.format("Frame[%s, %d bytes, \"%s\"]",
                type, payload.length, preview);
    }

    // ── Static I/O Methods ─────────────────────────────────────────────

    /**
     * Write a frame to a DataOutputStream. Thread-safe if the caller
     * synchronizes on the stream or uses a FrameWriter.
     */
    public static void write(DataOutputStream out, Frame frame) throws IOException {
        int length = 1 + frame.payload.length; // type byte + payload
        out.writeInt(length);
        out.writeByte(frame.type.code());
        if (frame.payload.length > 0) {
            out.write(frame.payload);
        }
        out.flush();
    }

    /**
     * Read a frame from a DataInputStream. Blocks until a complete frame
     * is available. Throws EOFException on clean disconnect.
     *
     * @throws IOException     on I/O error or malformed data
     * @throws EOFException    when the peer closes the connection
     */
    public static Frame read(DataInputStream in) throws IOException {
        // Read the 4-byte length prefix
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            throw e; // clean disconnect
        }

        // Validate length
        if (length < 1) {
            throw new IOException("Invalid frame: length=" + length + " (must be >= 1)");
        }
        if (length > Constants.MAX_FRAME_SIZE) {
            throw new IOException("Frame too large: " + length + " bytes (max "
                    + Constants.MAX_FRAME_SIZE + ")");
        }

        // Read type byte
        byte typeCode = in.readByte();
        Protocol type = Protocol.fromCode(typeCode);
        if (type == null) {
            throw new IOException("Unknown frame type: 0x"
                    + Integer.toHexString(typeCode & 0xFF));
        }

        // Read payload (length - 1 because we already read the type byte)
        byte[] payload = new byte[length - 1];
        if (payload.length > 0) {
            in.readFully(payload);
        }

        return new Frame(type, payload);
    }

    // ── Convenience Builders ───────────────────────────────────────────

    /** Create streams from a socket for frame-based I/O. */
    public static DataOutputStream outputStream(Socket socket) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public static DataInputStream inputStream(Socket socket) throws IOException {
        return new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }
}
