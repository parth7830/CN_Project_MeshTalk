package meshtalk.common;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Thread-safe wrapper for writing frames to a TCP connection.
 * Synchronizes on the output stream so multiple threads can send
 * frames to the same client without interleaving bytes.
 */
public class FrameWriter {

    private final DataOutputStream out;

    public FrameWriter(DataOutputStream out) {
        this.out = out;
    }

    /** Write a frame atomically (thread-safe). */
    public void write(Frame frame) throws IOException {
        synchronized (out) {
            Frame.write(out, frame);
        }
    }

    /** Write a text frame atomically. */
    public void write(Protocol type, String text) throws IOException {
        write(new Frame(type, text));
    }

    /** Write a binary frame atomically. */
    public void write(Protocol type, byte[] data) throws IOException {
        write(new Frame(type, data));
    }

    /** Write a frame with no payload. */
    public void write(Protocol type) throws IOException {
        write(new Frame(type));
    }

    /** Flush and close the underlying stream. */
    public void close() {
        try { out.close(); } catch (IOException ignored) {}
    }
}
