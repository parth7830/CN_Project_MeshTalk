package meshtalk.server;

import meshtalk.common.MeshLogger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrency Demonstrations for University Viva & Evaluation:
 * 1. Race Condition: Unsafe shared counter vs. Synchronized / Atomic counter.
 * 2. Deadlock: Inconsistent lock ordering causing circular wait vs. Strict Lock Ordering fix.
 */
public class ConcurrencyDemo {

    private static final MeshLogger log = new MeshLogger("ConcurrencyDemo");

    // ── 1. RACE CONDITION DEMO ──────────────────────────────────────────

    private static int unsafeCount = 0;
    private static final Object lock = new Object();
    private static int safeCount = 0;
    private static final AtomicInteger atomicCount = new AtomicInteger(0);

    /**
     * Runs N threads incrementing an unsynchronized primitive int M times.
     * Demonstrates lost updates due to non-atomic read-modify-write.
     */
    public static String demonstrateRaceCondition(int numThreads, int incrementsPerThread) throws InterruptedException {
        unsafeCount = 0;
        safeCount = 0;
        atomicCount.set(0);

        int expected = numThreads * incrementsPerThread;

        // Unsafe run
        Thread[] threadsUnsafe = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threadsUnsafe[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    unsafeCount++; // NOT atomic: read, add 1, write back
                }
            });
            threadsUnsafe[i].start();
        }
        for (Thread t : threadsUnsafe) t.join();

        // Safe run using AtomicInteger
        Thread[] threadsSafe = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threadsSafe[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    atomicCount.incrementAndGet(); // Atomic hardware CAS
                }
            });
            threadsSafe[i].start();
        }
        for (Thread t : threadsSafe) t.join();

        StringBuilder sb = new StringBuilder();
        sb.append("=== RACE CONDITION DEMONSTRATION ===\n");
        sb.append(String.format("Threads: %d, Iterations per thread: %d\n", numThreads, incrementsPerThread));
        sb.append(String.format("Expected Total Count: %d\n\n", expected));
        sb.append(String.format("1. [UNSAFE COUNTER] Result = %d (Lost updates: %d)\n",
                unsafeCount, expected - unsafeCount));
        sb.append("   Root Cause: 'count++' is 3 bytecode operations (GETFIELD, IADD, PUTFIELD).\n");
        sb.append("   Interleaved thread execution overwrites memory.\n\n");
        sb.append(String.format("2. [FIXED - ATOMIC / SYNC] Result = %d (Exact match!)\n", atomicCount.get()));
        sb.append("   Solution: Hardware Compare-And-Swap (CAS) via AtomicInteger ensures atomicity.\n");

        return sb.toString();
    }

    // ── 2. DEADLOCK DEMO ────────────────────────────────────────────────

    private static final Object ResourceA = new Object();
    private static final Object ResourceB = new Object();

    /**
     * Simulates deadlock using opposite lock acquisitions:
     * Thread 1: Locks A, sleeps, tries to lock B.
     * Thread 2: Locks B, sleeps, tries to lock A.
     * Times out safely to avoid permanently hanging the JVM.
     */
    public static String demonstrateDeadlock(boolean withFix) {
        StringBuilder sb = new StringBuilder();
        sb.append(withFix ? "=== DEADLOCK PREVENTION (ORDERED LOCKING) ===\n" : "=== DEADLOCK SIMULATION (CIRCULAR WAIT) ===\n");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        if (!withFix) {
            sb.append("Scenario: Inconsistent acquisition order creates Circular Wait.\n");
            sb.append("  Thread-1: Lock(A) -> Lock(B)\n");
            sb.append("  Thread-2: Lock(B) -> Lock(A)\n\n");

            Thread t1 = new Thread(() -> {
                synchronized (ResourceA) {
                    try {
                        startLatch.await();
                        Thread.sleep(50);
                        synchronized (ResourceB) {
                            finishLatch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
            }, "Deadlock-Thread-1");

            Thread t2 = new Thread(() -> {
                synchronized (ResourceB) {
                    try {
                        startLatch.await();
                        Thread.sleep(50);
                        synchronized (ResourceA) {
                            finishLatch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
            }, "Deadlock-Thread-2");

            t1.start();
            t2.start();
            startLatch.countDown();

            try {
                boolean completed = finishLatch.await(500, TimeUnit.MILLISECONDS);
                if (!completed) {
                    sb.append("RESULT: DEADLOCK DETECTED! Both threads stuck waiting for each other's lock.\n");
                    sb.append("Coffman Conditions met: Mutual Exclusion, Hold & Wait, No Preemption, Circular Wait.\n");
                }
            } catch (InterruptedException e) {
                sb.append("Simulation interrupted.\n");
            }

        } else {
            sb.append("Scenario: Strict Lock Ordering Strategy (Always lock A before B).\n");
            sb.append("  Thread-1: Lock(A) -> Lock(B)\n");
            sb.append("  Thread-2: Lock(A) -> Lock(B)\n\n");

            Thread t1 = new Thread(() -> {
                synchronized (ResourceA) {
                    try {
                        startLatch.await();
                        Thread.sleep(20);
                        synchronized (ResourceB) {
                            finishLatch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
            }, "Fixed-Thread-1");

            Thread t2 = new Thread(() -> {
                synchronized (ResourceA) {
                    try {
                        startLatch.await();
                        Thread.sleep(20);
                        synchronized (ResourceB) {
                            finishLatch.countDown();
                        }
                    } catch (Exception ignored) {}
                }
            }, "Fixed-Thread-2");

            t1.start();
            t2.start();
            startLatch.countDown();

            try {
                boolean completed = finishLatch.await(500, TimeUnit.MILLISECONDS);
                if (completed) {
                    sb.append("RESULT: SUCCESS! No deadlock. Both threads acquired locks and finished cleanly.\n");
                    sb.append("Fix Explained: Enforcing a global lock hierarchy eliminates Circular Wait.\n");
                }
            } catch (InterruptedException e) {
                sb.append("Simulation interrupted.\n");
            }
        }

        return sb.toString();
    }
}
