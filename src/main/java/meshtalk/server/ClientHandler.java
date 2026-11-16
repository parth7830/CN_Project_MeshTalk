package meshtalk.server;

import meshtalk.common.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles communication for a single client connection.
 * Reads length-prefixed frames, validates protocol invariants,
 * and coordinates broadcast, private messaging, rooms, and file relays.
 */
public class ClientHandler implements Runnable {

    private static final MeshLogger log = new MeshLogger("ClientHandler");

    private final Socket socket;
    private final ConcurrentHashMap<String, ClientSession> registry;
    private final ServerMetrics metrics;
    private final List<String> chatHistory;
    private final Map<String, ChatRoom> rooms;
    private final FileTransferManager fileManager;

    private ClientSession session;
    private String username;

    public ClientHandler(Socket socket,
                         ConcurrentHashMap<String, ClientSession> registry,
                         ServerMetrics metrics,
                         List<String> chatHistory,
                         Map<String, ChatRoom> rooms,
                         FileTransferManager fileManager) {
        this.socket = socket;
        this.registry = registry;
        this.metrics = metrics;
        this.chatHistory = chatHistory;
        this.rooms = rooms;
        this.fileManager = fileManager;
    }

    @Override
    public void run() {
        DataInputStream in = null;
        DataOutputStream out = null;

        try {
            socket.setSoTimeout(Constants.READ_TIMEOUT);
            in = Frame.inputStream(socket);
            out = Frame.outputStream(socket);
            FrameWriter writer = new FrameWriter(out);

            // First frame MUST be LOGIN
            Frame firstFrame = Frame.read(in);
            if (firstFrame.getType() != Protocol.LOGIN) {
                writer.write(Protocol.LOGIN_FAIL, "First frame must be LOGIN");
                return;
            }

            String requestedName = firstFrame.getText().trim();
            if (requestedName.isEmpty() || requestedName.contains(",") || requestedName.contains("\0")) {
                writer.write(Protocol.LOGIN_FAIL, "Invalid username format");
                return;
            }

            // Enforce uniqueness in registry
            synchronized (registry) {
                if (registry.containsKey(requestedName)) {
                    writer.write(Protocol.LOGIN_FAIL, "Username already taken: " + requestedName);
                    return;
                }
                this.username = requestedName;
                this.session = new ClientSession(username, socket, writer, in);
                registry.put(username, session);
            }

            metrics.clientConnected();
            log.info("Client logged in: '%s' from %s", username, socket.getRemoteSocketAddress());
            writer.write(Protocol.LOGIN_OK, "Welcome to MeshTalk, " + username + "!");

            // Broadcast JOIN to all other users
            broadcast(new Frame(Protocol.JOIN, username), username);

            // Send chat history
            synchronized (chatHistory) {
                if (!chatHistory.isEmpty()) {
                    String historyText = String.join("\n", chatHistory);
                    writer.write(Protocol.HISTORY, historyText);
                }
            }

            // Frame processing loop
            while (!socket.isClosed()) {
                Frame frame;
                try {
                    frame = Frame.read(in);
                } catch (SocketTimeoutException ste) {
                    // Check heartbeat expiration
                    if (session.isExpired()) {
                        log.warn("Client '%s' timed out (no heartbeat)", username);
                        break;
                    }
                    continue;
                } catch (EOFException | SocketException e) {
                    // Normal or abrupt disconnect
                    break;
                }

                session.updateHeartbeat();
                session.addBytesIn(frame.wireSize());
                metrics.bytesTransferred(frame.wireSize());

                processFrame(frame);
            }

        } catch (Exception e) {
            log.debug("Session error for '%s': %s", username != null ? username : "anonymous", e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void processFrame(Frame frame) throws IOException {
        metrics.messageReceived();

        switch (frame.getType()) {
            case MSG: {
                String text = frame.getText();
                String formatted = username + ": " + text;
                log.info("[BROADCAST] %s", formatted);

                synchronized (chatHistory) {
                    if (chatHistory.size() >= Constants.CHAT_HISTORY_SIZE) {
                        chatHistory.remove(0);
                    }
                    chatHistory.add(formatted);
                }

                // Relay to all online users
                Frame relayFrame = new Frame(Protocol.MSG_RELAY, username + "\0" + text);
                broadcast(relayFrame, null);
                break;
            }

            case PRIVATE: {
                String payload = frame.getText();
                int nullIdx = payload.indexOf('\0');
                if (nullIdx > 0) {
                    String target = payload.substring(0, nullIdx);
                    String msg = payload.substring(nullIdx + 1);
                    ClientSession targetSession = registry.get(target);
                    if (targetSession != null) {
                        targetSession.sendFrame(new Frame(Protocol.PRIVATE_RELAY, username + "\0" + msg));
                        session.sendFrame(new Frame(Protocol.ACK, "Message sent to " + target));
                    } else {
                        session.sendFrame(new Frame(Protocol.ERROR, "User not found: " + target));
                    }
                }
                break;
            }

            case USERS: {
                String userList = String.join(",", registry.keySet());
                session.sendFrame(new Frame(Protocol.USERS, userList));
                break;
            }

            case PING: {
                session.sendFrame(new Frame(Protocol.PONG));
                break;
            }

            case PONG: {
                // Heartbeat updated
                break;
            }

            case FILE_OFFER: {
                // payload: target\0filename\0filesize
                String[] parts = frame.getText().split("\0");
                if (parts.length >= 3) {
                    String target = parts[0];
                    String filename = parts[1];
                    long size = Long.parseLong(parts[2]);
                    ClientSession targetSession = registry.get(target);
                    if (targetSession != null) {
                        fileManager.registerTransfer(username, target, filename, size);
                        targetSession.sendFrame(new Frame(Protocol.FILE_ACCEPT, username + "\0" + filename + "\0" + size));
                    } else {
                        session.sendFrame(new Frame(Protocol.ERROR, "Recipient not found: " + target));
                    }
                }
                break;
            }

            case FILE_CHUNK: {
                FileTransferManager.ActiveTransfer transfer = fileManager.getTransfer(username);
                if (transfer != null) {
                    ClientSession targetSession = registry.get(transfer.receiver);
                    if (targetSession != null) {
                        targetSession.sendFrame(frame);
                        fileManager.updateProgress(username, frame.getPayload().length);
                    }
                }
                break;
            }

            case FILE_END: {
                FileTransferManager.ActiveTransfer transfer = fileManager.completeTransfer(username);
                if (transfer != null) {
                    ClientSession targetSession = registry.get(transfer.receiver);
                    if (targetSession != null) {
                        targetSession.sendFrame(frame); // relays SHA-256 hash
                    }
                }
                break;
            }

            case FILE_ACK: {
                // Relay verification ACK back to sender
                String payload = frame.getText();
                int idx = payload.indexOf('\0');
                if (idx > 0) {
                    String sender = payload.substring(0, idx);
                    String result = payload.substring(idx + 1);
                    ClientSession senderSession = registry.get(sender);
                    if (senderSession != null) {
                        senderSession.sendFrame(new Frame(Protocol.FILE_ACK, result));
                    }
                }
                break;
            }

            case ROOM_CREATE: {
                String roomName = frame.getText().trim();
                rooms.putIfAbsent(roomName, new ChatRoom(roomName));
                session.sendFrame(new Frame(Protocol.ACK, "Room created: " + roomName));
                break;
            }

            case ROOM_JOIN: {
                String rName = frame.getText().trim();
                ChatRoom cr = rooms.get(rName);
                if (cr != null) {
                    cr.addMember(username);
                    session.joinRoom(rName);
                    session.sendFrame(new Frame(Protocol.ACK, "Joined room: " + rName));
                } else {
                    session.sendFrame(new Frame(Protocol.ERROR, "Room does not exist: " + rName));
                }
                break;
            }

            case ROOM_LEAVE: {
                String rName = frame.getText().trim();
                ChatRoom cr = rooms.get(rName);
                if (cr != null) {
                    cr.removeMember(username);
                    session.leaveRoom(rName);
                    session.sendFrame(new Frame(Protocol.ACK, "Left room: " + rName));
                }
                break;
            }

            case ROOM_MSG: {
                String payload = frame.getText();
                int idx = payload.indexOf('\0');
                if (idx > 0) {
                    String rName = payload.substring(0, idx);
                    String text = payload.substring(idx + 1);
                    ChatRoom cr = rooms.get(rName);
                    if (cr != null && session.getRooms().contains(rName)) {
                        cr.broadcast(username, text, registry);
                    }
                }
                break;
            }

            case ROOM_LIST: {
                String rList = String.join(",", rooms.keySet());
                session.sendFrame(new Frame(Protocol.ROOM_LIST, rList));
                break;
            }

            case LOGOUT: {
                session.close();
                break;
            }

            default:
                log.warn("Unhandled protocol type: %s", frame.getType());
                break;
        }
    }

    private void broadcast(Frame frame, String excludeUser) {
        for (Map.Entry<String, ClientSession> entry : registry.entrySet()) {
            if (excludeUser == null || !entry.getKey().equals(excludeUser)) {
                try {
                    entry.getValue().sendFrame(frame);
                } catch (IOException e) {
                    log.debug("Failed broadcast to %s: %s", entry.getKey(), e.getMessage());
                }
            }
        }
    }

    private void cleanup() {
        if (username != null) {
            registry.remove(username);
            metrics.clientDisconnected();
            log.info("Client '%s' disconnected and unregistered.", username);

            // Leave all rooms
            for (ChatRoom cr : rooms.values()) {
                cr.removeMember(username);
            }

            // Broadcast LEAVE notification
            broadcast(new Frame(Protocol.LEAVE, username), null);
        }

        if (session != null) {
            session.close();
        } else {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
