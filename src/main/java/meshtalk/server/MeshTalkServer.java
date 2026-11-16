package meshtalk.server;

import meshtalk.common.Constants;
import meshtalk.common.MeshLogger;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main server for MeshTalk.
 * Supports three concurrency architectures:
 *  1. SINGLE: Processes one connection at a time (baseline bottleneck)
 *  2. THREAD_PER_CLIENT: Spawns an unbound new Thread per connection
 *  3. THREAD_POOL: Uses a bounded fixed ExecutorService thread pool
 */
public class MeshTalkServer {

    private static final MeshLogger log = new MeshLogger("Server");

    private final int port;
    private final ServerMode mode;
    private final int poolSize;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread heartbeatThread;
    private ExecutorService threadPool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Shared state
    private final ConcurrentHashMap<String, ClientSession> registry = new ConcurrentHashMap<>();
    private final List<String> chatHistory = Collections.synchronizedList(new LinkedList<>());
    private final Map<String, ChatRoom> rooms = new ConcurrentHashMap<>();
    private final ServerMetrics metrics = new ServerMetrics();
    private final FileTransferManager fileManager = new FileTransferManager();
    private UdpService udpService;

    public MeshTalkServer(int port, ServerMode mode, int poolSize) {
        this.port = port;
        this.mode = mode;
        this.poolSize = Math.max(1, poolSize);
    }

    public MeshTalkServer() {
        this(Constants.DEFAULT_TCP_PORT, ServerMode.THREAD_POOL, Constants.DEFAULT_POOL_SIZE);
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;

        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running.set(true);

        if (mode == ServerMode.THREAD_POOL) {
            ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                    poolSize, poolSize,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadFactory() {
                        private int count = 0;
                        @Override
                        public Thread newThread(Runnable r) {
                            return new Thread(r, "MeshWorker-" + (++count));
                        }
                    }
            );
            this.threadPool = tpe;
        }

        // Start UDP companion service
        try {
            udpService = new UdpService(Constants.DEFAULT_UDP_PORT, port, metrics);
            udpService.start();
        } catch (Exception e) {
            log.warn("Could not start UDP service on %d: %s", Constants.DEFAULT_UDP_PORT, e.getMessage());
        }

        // Accept loop
        acceptThread = new Thread(this::runAcceptLoop, "MeshTalk-AcceptLoop");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // Heartbeat scanner
        heartbeatThread = new Thread(this::runHeartbeatScanner, "MeshTalk-HeartbeatScanner");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        log.info("MeshTalk Server started on port %d [Mode: %s, PoolSize: %d]", port, mode, poolSize);
    }

    private void runAcceptLoop() {
        while (running.get() && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(
                        clientSocket, registry, metrics, chatHistory, rooms, fileManager);

                switch (mode) {
                    case SINGLE:
                        // Synchronous run on accept thread (bottleneck)
                        log.info("[SINGLE MODE] Handling client synchronously...");
                        handler.run();
                        break;

                    case THREAD_PER_CLIENT:
                        Thread t = new Thread(handler, "ClientThread-" + clientSocket.getRemoteSocketAddress());
                        t.setDaemon(true);
                        t.start();
                        metrics.setActiveThreads(Thread.activeCount());
                        break;

                    case THREAD_POOL:
                        ThreadPoolExecutor tpe = (ThreadPoolExecutor) threadPool;
                        tpe.submit(handler);
                        metrics.setActiveThreads(tpe.getActiveCount());
                        metrics.setQueueLength(tpe.getQueue().size());
                        break;
                }

            } catch (SocketException se) {
                if (!running.get()) break;
            } catch (IOException e) {
                log.error("Accept error: %s", e.getMessage());
            }
        }
    }

    private void runHeartbeatScanner() {
        while (running.get()) {
            try {
                Thread.sleep(Constants.HEARTBEAT_INTERVAL);

                // Scan registry for expired client sessions
                for (Map.Entry<String, ClientSession> entry : registry.entrySet()) {
                    ClientSession s = entry.getValue();
                    if (s.isExpired()) {
                        log.warn("Heartbeat expired for user '%s'. Closing connection.", s.getUsername());
                        s.close();
                    }
                }

                // Update thread pool metrics if applicable
                if (threadPool instanceof ThreadPoolExecutor tpe) {
                    metrics.setActiveThreads(tpe.getActiveCount());
                    metrics.setQueueLength(tpe.getQueue().size());
                }

            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                log.debug("Heartbeat scanner error: %s", e.getMessage());
            }
        }
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);

        log.info("Shutting down server...");

        // Close server socket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        // Disconnect all sessions
        for (ClientSession session : registry.values()) {
            session.close();
        }
        registry.clear();

        // Stop thread pool
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
            }
        }

        // Stop UDP service
        if (udpService != null) {
            udpService.stop();
        }

        log.info("Server stopped cleanly.");
    }

    // Getters for status and UI
    public int getPort() { return port; }
    public ServerMode getMode() { return mode; }
    public ServerMetrics getMetrics() { return metrics; }
    public ConcurrentHashMap<String, ClientSession> getRegistry() { return registry; }
    public Map<String, ChatRoom> getRooms() { return rooms; }
    public boolean isRunning() { return running.get(); }

    public static void main(String[] args) {
        int port = Constants.DEFAULT_TCP_PORT;
        ServerMode mode = ServerMode.THREAD_POOL;
        int poolSize = Constants.DEFAULT_POOL_SIZE;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
                mode = ServerMode.valueOf(args[++i].toUpperCase());
            } else if ("--pool-size".equals(args[i]) && i + 1 < args.length) {
                poolSize = Integer.parseInt(args[++i]);
            }
        }

        MeshTalkServer server = new MeshTalkServer(port, mode, poolSize);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "MeshShutdownHook"));

        try {
            server.start();
            log.info("Server is up. Press Ctrl+C to terminate.");
            // Keep main thread alive
            while (server.isRunning()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            log.error("Fatal server error: %s", e.getMessage());
            e.printStackTrace();
        }
    }
}
