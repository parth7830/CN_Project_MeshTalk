package meshtalk.server;

import meshtalk.common.Constants;
import meshtalk.common.Frame;
import meshtalk.common.FrameWriter;
import meshtalk.common.MeshLogger;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single connected client on the server side.
 *
 * Each ClientSession wraps the raw socket together with:
 *  - a thread-safe FrameWriter for sending frames
 *  - a DataInputStream for receiving frames
 *  - heartbeat tracking (lastHeartbeat timestamp)
 *  - byte counters for metrics
 *  - the set of chat rooms the user has joined
 *
 * A session is created after a client opens a TCP connection and is
 * registered in the server's ConcurrentHashMap once it sends LOGIN.
 */
public class ClientSession {

    private static final MeshLogger log = new MeshLogger("ClientSession");

    // ── Identity ───────────────────────────────────────────────────────
    private final String username;
    private final Socket socket;
    private final FrameWriter writer;
    private final DataInputStream inputStream;
    private final InetSocketAddress remoteAddress;

    // ── Timing ─────────────────────────────────────────────────────────
    private final long connectedAt;
    private volatile long lastHeartbeat;

    // ── Traffic metrics (atomic for thread safety) ─────────────────────
    private final AtomicLong bytesIn  = new AtomicLong(0);
    private final AtomicLong bytesOut = new AtomicLong(0);

    // ── Rooms this user belongs to ─────────────────────────────────────
    private final Set<String> rooms = ConcurrentHashMap.newKeySet();

    /**
     * Create a new session for a client that has just logged in.
     *
     * @param username    validated unique username
     * @param socket      the TCP connection socket
     * @param writer      thread-safe frame writer wrapping the socket output
     * @param inputStream buffered input stream for reading frames
     */
    public ClientSession(String username, Socket socket,
                         FrameWriter writer, DataInputStream inputStream) {
        this.username      = username;
        this.socket        = socket;
        this.writer        = writer;
        this.inputStream   = inputStream;
        this.remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
        this.connectedAt   = System.currentTimeMillis();
        this.lastHeartbeat = this.connectedAt;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public String getUsername()              { return username; }
    public Socket getSocket()               { return socket; }
    public FrameWriter getWriter()          { return writer; }
    public DataInputStream getInputStream() { return inputStream; }
    public InetSocketAddress getRemoteAddress() { return remoteAddress; }
    public long getConnectedAt()            { return connectedAt; }
    public long getLastHeartbeat()          { return lastHeartbeat; }
    public long getBytesIn()                { return bytesIn.get(); }
    public long getBytesOut()               { return bytesOut.get(); }
    public Set<String> getRooms()           { return Collections.unmodifiableSet(rooms); }

    // ── Actions ────────────────────────────────────────────────────────

    /**
     * Send a frame to this client. Thread-safe because FrameWriter
     * synchronises on the underlying DataOutputStream.
     */
    public void sendFrame(Frame frame) throws IOException {
        writer.write(frame);
        bytesOut.addAndGet(frame.wireSize());
    }

    /** Record incoming bytes for metrics tracking. */
    public void addBytesIn(long n) {
        bytesIn.addAndGet(n);
    }

    /** Mark the session as "just heard from". */
    public void updateHeartbeat() {
        lastHeartbeat = System.currentTimeMillis();
    }

    /**
     * Check whether this session has exceeded the heartbeat timeout.
     * If we haven't received any data from the client in HEARTBEAT_TIMEOUT ms,
     * the session is considered expired and should be cleaned up.
     */
    public boolean isExpired() {
        return (System.currentTimeMillis() - lastHeartbeat) > Constants.HEARTBEAT_TIMEOUT;
    }

    /** How long this session has been connected, in milliseconds. */
    public long getUptime() {
        return System.currentTimeMillis() - connectedAt;
    }

    /** Join a chat room. */
    public void joinRoom(String room) {
        rooms.add(room);
    }

    /** Leave a chat room. */
    public void leaveRoom(String room) {
        rooms.remove(room);
    }

    /**
     * Close the socket and the writer.  Safe to call multiple times.
     */
    public void close() {
        try { writer.close(); }  catch (Exception ignored) {}
        try { socket.close(); }  catch (Exception ignored) {}
        log.debug("Session closed for user '%s'", username);
    }

    @Override
    public String toString() {
        return String.format("ClientSession[%s, %s, uptime=%ds]",
                username, remoteAddress, getUptime() / 1000);
    }
}
