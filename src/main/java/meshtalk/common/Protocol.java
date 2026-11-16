package meshtalk.common;

/**
 * Message types used in the MeshTalk application-layer protocol.
 * Each type has a unique 1-byte code sent in the frame header.
 *
 * Frame format:  [4-byte length][1-byte type][payload bytes]
 *   - length: total number of bytes that follow (type + payload)
 *   - type:   one of the codes below
 *   - payload: UTF-8 text or binary data depending on the type
 */
public enum Protocol {

    // ── Connection Management ──────────────────────────────────────────
    LOGIN        ((byte) 0x01),   // C→S  payload: username
    LOGIN_OK     ((byte) 0x02),   // S→C  payload: welcome message
    LOGIN_FAIL   ((byte) 0x03),   // S→C  payload: reason
    LOGOUT       ((byte) 0x04),   // C→S  no payload
    PING         ((byte) 0x05),   // bidirectional heartbeat
    PONG         ((byte) 0x06),   // response to PING

    // ── Chat ───────────────────────────────────────────────────────────
    MSG          ((byte) 0x10),   // C→S  payload: text (broadcast)
    MSG_RELAY    ((byte) 0x11),   // S→C  payload: sender\0text
    PRIVATE      ((byte) 0x12),   // C→S  payload: target\0text
    PRIVATE_RELAY((byte) 0x13),   // S→C  payload: sender\0text
    USERS        ((byte) 0x14),   // C→S  request, S→C response: user list CSV
    JOIN         ((byte) 0x15),   // S→C  payload: username joined
    LEAVE        ((byte) 0x16),   // S→C  payload: username left
    HISTORY      ((byte) 0x17),   // S→C  payload: newline-separated messages

    // ── File Transfer (TCP) ────────────────────────────────────────────
    FILE_OFFER   ((byte) 0x20),   // C→S  payload: target\0filename\0filesize
    FILE_ACCEPT  ((byte) 0x21),   // S→C  payload: sender\0filename\0filesize
    FILE_CHUNK   ((byte) 0x22),   // bidirectional: binary chunk
    FILE_END     ((byte) 0x23),   // sender→receiver: payload: SHA-256 hex string
    FILE_ACK     ((byte) 0x24),   // receiver→sender: payload: VERIFIED or FAILED

    // ── Status / Errors ────────────────────────────────────────────────
    ERROR        ((byte) 0x30),   // S→C  payload: error description
    ACK          ((byte) 0x31),   // generic acknowledgement
    SERVER_INFO  ((byte) 0x32),   // S→C  payload: JSON with server stats

    // ── Room Management ────────────────────────────────────────────────
    ROOM_CREATE  ((byte) 0x40),   // C→S  payload: room name
    ROOM_JOIN    ((byte) 0x41),   // C→S  payload: room name
    ROOM_LEAVE   ((byte) 0x42),   // C→S  payload: room name
    ROOM_MSG     ((byte) 0x43),   // C→S  payload: room\0text
    ROOM_MSG_RELAY((byte)0x44),   // S→C  payload: room\0sender\0text
    ROOM_LIST    ((byte) 0x45);   // C→S request / S→C response

    private final byte code;

    Protocol(byte code) {
        this.code = code;
    }

    /** The 1-byte code written into the frame. */
    public byte code() {
        return code;
    }

    /** Look up a Protocol value from its byte code. Returns null if unknown. */
    public static Protocol fromCode(byte code) {
        for (Protocol p : values()) {
            if (p.code == code) return p;
        }
        return null;
    }
}
