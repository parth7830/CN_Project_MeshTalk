package meshtalk.common;

/**
 * Central constants for the MeshTalk application.
 * All ports, sizes, and defaults are defined here to avoid magic numbers.
 */
public final class Constants {

    private Constants() {} // no instances

    // ── Network Ports ──────────────────────────────────────────────────
    public static final int DEFAULT_TCP_PORT   = 5000;
    public static final int DEFAULT_UDP_PORT   = 5001;
    public static final int DEFAULT_RDT_PORT   = 5002;
    public static final int DISCOVERY_PORT     = 5003;

    // ── Server Defaults ────────────────────────────────────────────────
    public static final int DEFAULT_POOL_SIZE  = 16;
    public static final int MAX_FRAME_SIZE     = 10 * 1024 * 1024; // 10 MB max frame
    public static final int HEARTBEAT_INTERVAL = 5000;             // ms
    public static final int HEARTBEAT_TIMEOUT  = 15000;            // ms
    public static final int READ_TIMEOUT       = 30000;            // ms
    public static final int CHAT_HISTORY_SIZE  = 50;

    // ── RDT Defaults ───────────────────────────────────────────────────
    public static final int DEFAULT_WINDOW_SIZE = 8;
    public static final int RDT_MAX_PAYLOAD     = 1024;            // bytes per data packet
    public static final long INITIAL_RTO        = 1000;            // ms
    public static final long MIN_RTO            = 200;             // ms
    public static final long MAX_RTO            = 10000;           // ms
    public static final double ALPHA            = 0.125;           // EWMA for EstimatedRTT
    public static final double BETA             = 0.25;            // EWMA for DevRTT
    public static final int DUP_ACK_THRESHOLD   = 3;               // for fast retransmit

    // ── File Transfer ──────────────────────────────────────────────────
    public static final int FILE_CHUNK_SIZE = 4096;

    // ── Protocol Version ───────────────────────────────────────────────
    public static final byte PROTOCOL_VERSION = 1;

    // ── Discovery ──────────────────────────────────────────────────────
    public static final String DISCOVERY_MAGIC   = "MESHTALK_DISCOVER";
    public static final String DISCOVERY_RESPONSE = "MESHTALK_HERE";
}
