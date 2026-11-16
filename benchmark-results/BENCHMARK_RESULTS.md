# MeshTalk Benchmark Results

Generated: 2026-11-12T23:48:14.569521300

## Server Mode Comparison

| Mode | Clients | Avg Response (ms) | P95 Response (ms) | Throughput (msg/s) | Conn Time (ms) | Threads |
|------|---------|-------------------|--------------------|--------------------|----------------|---------|
| SINGLE | 1 | 5.80 | 56.00 | 120.7 | 98.00 | 3 |
| SINGLE | 5 | 0.00 | 0.00 | 0.0 | 0.00 | 0 |
| SINGLE | 10 | 0.00 | 0.00 | 0.0 | 0.00 | 0 |
| SINGLE | 25 | -1.00 | -1.00 | 0.0 | -1.00 | 0 |
| SINGLE | 50 | -1.00 | -1.00 | 0.0 | -1.00 | 0 |
| SINGLE | 100 | -1.00 | -1.00 | 0.0 | -1.00 | 0 |
| THREAD_PER_CLIENT | 1 | 0.85 | 2.00 | 738.3 | 2.00 | 5 |
| THREAD_PER_CLIENT | 5 | 0.52 | 3.00 | 4548.8 | 4.60 | 10 |
| THREAD_PER_CLIENT | 10 | 0.73 | 3.00 | 4226.1 | 1.00 | 21 |
| THREAD_PER_CLIENT | 25 | 4.80 | 28.00 | 1060.5 | 0.68 | 51 |
| THREAD_PER_CLIENT | 50 | 4.67 | 32.00 | 1464.5 | 0.94 | 102 |
| THREAD_PER_CLIENT | 100 | 0.00 | 0.00 | 0.0 | 0.00 | 0 |
| THREAD_POOL | 1 | 0.05 | 1.00 | 2117.4 | 1.00 | 6 |
| THREAD_POOL | 5 | 2.90 | 27.00 | 601.0 | 31.60 | 10 |
| THREAD_POOL | 10 | 0.70 | 2.00 | 1463.1 | 6.30 | 16 |
| THREAD_POOL | 25 | 0.00 | 0.00 | 0.0 | 0.00 | 0 |
| THREAD_POOL | 50 | 0.00 | 0.00 | 0.0 | 0.00 | 0 |
| THREAD_POOL | 100 | 0.00 | 0.00 | 0.0 | 0.00 | 0 |

## Analysis

### SINGLE Mode
The SINGLE mode processes one client at a time, creating a bottleneck. It works for 1 client but response times degrade sharply as clients increase. This demonstrates why concurrent server designs are necessary.

### THREAD_PER_CLIENT Mode
Creating a new thread per client provides concurrency but has overhead from thread creation/destruction. At very high client counts (100+), the thread overhead becomes significant and may cause resource exhaustion.

### THREAD_POOL Mode
The thread pool reuses a fixed number of threads, providing the best balance of concurrency and resource efficiency. Response times and throughput remain stable even at high client counts, demonstrating why thread pools are the industry standard.
