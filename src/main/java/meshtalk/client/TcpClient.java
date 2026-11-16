package meshtalk.client;

import meshtalk.common.*;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP Chat & File Transfer Client for MeshTalk.
 * Maintains persistent TCP connection with server, handles framing,
 * background reception, heartbeats, and file transfers with SHA-256 verification.
 */
public class TcpClient {

    private static final MeshLogger log = new MeshLogger("TcpClient");

    public interface MessageListener {
        void onMessage(String sender, String text);
        void onPrivateMessage(String sender, String text);
        void onUserJoined(String user);
        void onUserLeft(String user);
        void onUserList(List<String> users);
        void onHistory(List<String> history);
        void onFileOffer(String sender, String filename, long size);
        void onFileProgress(String filename, double progress);
        void onFileComplete(String filename, boolean verified);
        void onRoomMessage(String room, String sender, String text);
        void onError(String error);
        void onDisconnect();
    }

    private final String host;
    private final int port;
    private String username;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private FrameWriter writer;

    private Thread readerThread;
    private ScheduledExecutorService heartbeatExecutor;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private MessageListener listener;

    // Incoming file assembly state
    private ByteArrayOutputStream incomingFileBuffer;
    private String incomingFilename;
    private long incomingFileSize;
    private String incomingFileSender;

    public TcpClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setMessageListener(MessageListener listener) {
        this.listener = listener;
    }

    public synchronized boolean connectAndLogin(String username) throws IOException {
        this.username = username;
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(Constants.READ_TIMEOUT);
        this.in = Frame.inputStream(socket);
        this.out = Frame.outputStream(socket);
        this.writer = new FrameWriter(out);

        // Send LOGIN frame
        writer.write(Protocol.LOGIN, username);

        // Read response
        Frame resp = Frame.read(in);
        if (resp.getType() == Protocol.LOGIN_OK) {
            connected.set(true);
            startBackgroundReader();
            startHeartbeat();
            log.info("Logged in as '%s' successfully.", username);
            return true;
        } else {
            String reason = resp.getText();
            log.error("Login rejected: %s", reason);
            if (listener != null) listener.onError("Login failed: " + reason);
            disconnect();
            return false;
        }
    }

    private void startBackgroundReader() {
        readerThread = new Thread(() -> {
            while (connected.get() && !socket.isClosed()) {
                try {
                    Frame frame = Frame.read(in);
                    dispatchFrame(frame);
                } catch (SocketTimeoutException ste) {
                    // Normal idle timeout
                } catch (EOFException | SocketException e) {
                    if (connected.get()) {
                        log.info("Disconnected from server.");
                        disconnect();
                    }
                    break;
                } catch (IOException e) {
                    log.error("Frame read error: %s", e.getMessage());
                    break;
                }
            }
        }, "TcpClient-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void startHeartbeat() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TcpClient-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (connected.get()) {
                try {
                    writer.write(Protocol.PING);
                } catch (Exception ignored) {}
            }
        }, Constants.HEARTBEAT_INTERVAL, Constants.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void dispatchFrame(Frame frame) {
        if (listener == null) return;

        switch (frame.getType()) {
            case MSG_RELAY: {
                String payload = frame.getText();
                int idx = payload.indexOf('\0');
                if (idx > 0) {
                    String sender = payload.substring(0, idx);
                    String text = payload.substring(idx + 1);
                    listener.onMessage(sender, text);
                }
                break;
            }

            case PRIVATE_RELAY: {
                String payload = frame.getText();
                int idx = payload.indexOf('\0');
                if (idx > 0) {
                    String sender = payload.substring(0, idx);
                    String text = payload.substring(idx + 1);
                    listener.onPrivateMessage(sender, text);
                }
                break;
            }

            case JOIN:
                listener.onUserJoined(frame.getText());
                break;

            case LEAVE:
                listener.onUserLeft(frame.getText());
                break;

            case USERS: {
                String[] users = frame.getText().split(",");
                listener.onUserList(Arrays.asList(users));
                break;
            }

            case HISTORY: {
                String[] lines = frame.getText().split("\n");
                listener.onHistory(Arrays.asList(lines));
                break;
            }

            case FILE_ACCEPT: {
                // Sender\0filename\0size
                String[] parts = frame.getText().split("\0");
                if (parts.length >= 3) {
                    incomingFileSender = parts[0];
                    incomingFilename = parts[1];
                    incomingFileSize = Long.parseLong(parts[2]);
                    incomingFileBuffer = new ByteArrayOutputStream();
                    listener.onFileOffer(incomingFileSender, incomingFilename, incomingFileSize);
                }
                break;
            }

            case FILE_CHUNK: {
                if (incomingFileBuffer != null) {
                    incomingFileBuffer.writeBytes(frame.getPayload());
                    double prog = (double) incomingFileBuffer.size() / incomingFileSize;
                    listener.onFileProgress(incomingFilename, Math.min(1.0, prog));
                }
                break;
            }

            case FILE_END: {
                if (incomingFileBuffer != null) {
                    byte[] fileBytes = incomingFileBuffer.toByteArray();
                    String expectedHash = frame.getText().trim();
                    String computedHash = ChecksumUtil.sha256Hex(fileBytes);
                    boolean match = expectedHash.equalsIgnoreCase(computedHash);

                    // Save received file to download folder or temp
                    try {
                        File downloadDir = new File("downloads");
                        downloadDir.mkdirs();
                        File dest = new File(downloadDir, incomingFilename);
                        Files.write(dest.toPath(), fileBytes);
                        log.info("File saved to %s (Hash match: %b)", dest.getAbsolutePath(), match);
                    } catch (IOException e) {
                        log.error("Could not write received file: %s", e.getMessage());
                    }

                    // Send ACK back to sender
                    try {
                        writer.write(Protocol.FILE_ACK, incomingFileSender + "\0" + (match ? "VERIFIED" : "FAILED"));
                    } catch (Exception ignored) {}

                    listener.onFileComplete(incomingFilename, match);
                    incomingFileBuffer = null;
                }
                break;
            }

            case FILE_ACK: {
                boolean verified = "VERIFIED".equalsIgnoreCase(frame.getText());
                listener.onFileComplete("Sent file", verified);
                break;
            }

            case ROOM_MSG_RELAY: {
                String payload = frame.getText();
                String[] parts = payload.split("\0", 3);
                if (parts.length >= 3) {
                    listener.onRoomMessage(parts[0], parts[1], parts[2]);
                }
                break;
            }

            case ERROR:
                listener.onError(frame.getText());
                break;

            case PONG:
            case ACK:
                break;

            default:
                break;
        }
    }

    public void sendMessage(String text) throws IOException {
        if (!connected.get()) throw new IOException("Not connected");
        writer.write(Protocol.MSG, text);
    }

    public void sendPrivateMessage(String target, String text) throws IOException {
        if (!connected.get()) throw new IOException("Not connected");
        writer.write(Protocol.PRIVATE, target + "\0" + text);
    }

    public void requestUserList() throws IOException {
        if (!connected.get()) throw new IOException("Not connected");
        writer.write(Protocol.USERS);
    }

    public void sendFile(String targetUser, File file) throws IOException {
        if (!connected.get()) throw new IOException("Not connected");
        if (!file.exists() || !file.isFile()) throw new FileNotFoundException("File does not exist: " + file);

        byte[] allBytes = Files.readAllBytes(file.toPath());
        String sha256 = ChecksumUtil.sha256Hex(allBytes);

        // 1. Offer file
        writer.write(Protocol.FILE_OFFER, targetUser + "\0" + file.getName() + "\0" + allBytes.length);

        // 2. Send chunks
        int offset = 0;
        while (offset < allBytes.length) {
            int len = Math.min(Constants.FILE_CHUNK_SIZE, allBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(allBytes, offset, offset + len);
            writer.write(Protocol.FILE_CHUNK, chunk);
            offset += len;
        }

        // 3. Send end marker with SHA-256
        writer.write(Protocol.FILE_END, sha256);
        log.info("File '%s' sent to %s. Awaiting verification ACK.", file.getName(), targetUser);
    }

    public synchronized void disconnect() {
        if (!connected.getAndSet(false)) return;

        try {
            if (writer != null) {
                writer.write(Protocol.LOGOUT);
            }
        } catch (Exception ignored) {}

        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}

        if (listener != null) {
            listener.onDisconnect();
        }
    }

    public boolean isConnected() { return connected.get(); }
    public String getUsername() { return username; }
}
