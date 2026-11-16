package meshtalk.server;

/**
 * Enumeration of the three concurrency models the MeshTalk server can run in.
 *
 * SINGLE          – one client at a time (iterative server, good for debugging)
 * THREAD_PER_CLIENT – a new thread for every connection (simple but unbounded)
 * THREAD_POOL     – fixed-size thread pool via ExecutorService (production-ready)
 */
public enum ServerMode {
    SINGLE,
    THREAD_PER_CLIENT,
    THREAD_POOL
}
