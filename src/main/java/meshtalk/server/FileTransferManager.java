package meshtalk.server;

import meshtalk.common.ChecksumUtil;
import meshtalk.common.Frame;
import meshtalk.common.MeshLogger;
import meshtalk.common.Protocol;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages TCP file relaying between clients.
 */
public class FileTransferManager {

    private static final MeshLogger log = new MeshLogger("FileTransferManager");

    public static class ActiveTransfer {
        public final String sender;
        public final String receiver;
        public final String filename;
        public final long fileSize;
        public long bytesRelayed;
        public final long startTime;

        public ActiveTransfer(String sender, String receiver, String filename, long fileSize) {
            this.sender = sender;
            this.receiver = receiver;
            this.filename = filename;
            this.fileSize = fileSize;
            this.startTime = System.currentTimeMillis();
        }
    }

    private final Map<String, ActiveTransfer> transfers = new ConcurrentHashMap<>();

    public void registerTransfer(String sender, String receiver, String filename, long fileSize) {
        ActiveTransfer t = new ActiveTransfer(sender, receiver, filename, fileSize);
        transfers.put(sender, t);
        log.info("Registered file transfer from %s to %s (%s, %d bytes)", sender, receiver, filename, fileSize);
    }

    public ActiveTransfer getTransfer(String sender) {
        return transfers.get(sender);
    }

    public void updateProgress(String sender, int bytes) {
        ActiveTransfer t = transfers.get(sender);
        if (t != null) {
            t.bytesRelayed += bytes;
        }
    }

    public ActiveTransfer completeTransfer(String sender) {
        return transfers.remove(sender);
    }
}
