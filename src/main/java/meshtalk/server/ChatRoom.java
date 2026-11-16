package meshtalk.server;

import meshtalk.common.Constants;
import meshtalk.common.Frame;
import meshtalk.common.MeshLogger;
import meshtalk.common.Protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a named chat room that users can create, join, and leave.
 *
 * Members are stored in a thread-safe ConcurrentHashMap-backed set.
 * Chat history is kept in a bounded LinkedList (last N messages, where
 * N = Constants.CHAT_HISTORY_SIZE) and is synchronized for safe access.
 *
 * Broadcast within the room relays ROOM_MSG_RELAY frames to every member
 * who is currently online (looked up via the server's session registry).
 */
public class ChatRoom {

    private static final MeshLogger log = new MeshLogger("ChatRoom");

    private final String name;

    // Thread-safe set of usernames in this room
    private final Set<String> members = ConcurrentHashMap.newKeySet();

    // Bounded history list — synchronized externally
    private final LinkedList<String> history = new LinkedList<>();

    public ChatRoom(String name) {
        this.name = name;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    /**
     * Return an unmodifiable snapshot of current members.
     */
    public Set<String> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    /**
     * Return a snapshot of the room's message history.
     */
    public List<String> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    // ── Member management ──────────────────────────────────────────────

    /**
     * Add a user to this room.
     *
     * @return true if the user was not already a member
     */
    public boolean addMember(String username) {
        boolean added = members.add(username);
        if (added) {
            log.info("User '%s' joined room '%s'", username, name);
        }
        return added;
    }

    /**
     * Remove a user from this room.
     *
     * @return true if the user was a member and was removed
     */
    public boolean removeMember(String username) {
        boolean removed = members.remove(username);
        if (removed) {
            log.info("User '%s' left room '%s'", username, name);
        }
        return removed;
    }

    /**
     * Check whether a user is a member of this room.
     */
    public boolean isMember(String username) {
        return members.contains(username);
    }

    // ── Messaging ──────────────────────────────────────────────────────

    /**
     * Broadcast a ROOM_MSG_RELAY frame to every member of this room.
     *
     * Payload format: room\0sender\0text
     * The sender does NOT receive their own message back.
     *
     * @param sender   the username of the sender
     * @param text     the message text
     * @param registry the global session registry to look up FrameWriters
     */
    public void broadcast(String sender, String text,
                          ConcurrentHashMap<String, ClientSession> registry) {
        // Build the relay payload: room\0sender\0text
        String payload = name + "\0" + sender + "\0" + text;
        Frame frame = new Frame(Protocol.ROOM_MSG_RELAY, payload);

        // Add to history
        String historyEntry = sender + ": " + text;
        synchronized (history) {
            history.addLast(historyEntry);
            while (history.size() > Constants.CHAT_HISTORY_SIZE) {
                history.removeFirst();
            }
        }

        // Send to every member except the sender
        for (String member : members) {
            if (member.equals(sender)) continue;
            ClientSession session = registry.get(member);
            if (session != null) {
                try {
                    session.sendFrame(frame);
                } catch (Exception e) {
                    log.warn("Failed to send room message to '%s': %s", member, e.getMessage());
                }
            }
        }
    }

    @Override
    public String toString() {
        return String.format("ChatRoom[%s, %d members]", name, members.size());
    }
}
