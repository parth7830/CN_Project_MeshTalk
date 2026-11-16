# MeshTalk Viva Voce Examination Guide: 50+ Questions & Answers

## 1. Sockets & Socket Programming

### Q1: What is a Socket?
**A:** A socket is an abstraction for an endpoint of bidirectional network communication between two programs. It is uniquely identified by the combination of an IP address, a transport protocol (TCP or UDP), and a port number.

### Q2: What is the difference between `ServerSocket` and `Socket` in Java?
**A:** `ServerSocket` is used by the server to bind to a specific port and listen for incoming connection requests via its blocking `accept()` method. `Socket` represents an active established peer-to-peer TCP connection used by both client and server to transfer data via `InputStream` and `OutputStream`.

### Q3: Why does `ServerSocket.accept()` block?
**A:** `accept()` blocks the calling thread until a client completes the TCP 3-way handshake (SYN, SYN-ACK, ACK) and enters the OS kernel's accept queue. Once a connection is established, `accept()` returns a new `Socket` dedicated to that client.

### Q4: Why did we put `accept()` on its own dedicated thread?
**A:** If `accept()` ran on the main thread, the main thread would be blocked waiting for new connections and unable to handle server management, graceful shutdowns, or metrics reporting.

### Q5: What is the purpose of `socket.setSoTimeout()`?
**A:** It sets the maximum time (in milliseconds) a blocking `read()` call will wait for incoming data. If the timer expires before any data arrives, a `java.net.SocketTimeoutException` is thrown, preventing threads from hanging indefinitely on dead connections.

### Q6: What does `serverSocket.setReuseAddress(true)` do?
**A:** It enables the `SO_REUSEADDR` socket option, allowing the server to bind to a port that is currently in the `TIME_WAIT` state from a recently closed connection. This prevents `BindException: Address already in use` upon rapid server restarts.

### Q7: What happens when a TCP client abruptly crashes or loses power?
**A:** TCP does not immediately notify the server of an abrupt disconnection if no packets are in transit. The server discovers this either when attempting to write to the socket (receiving `SocketException: Connection reset`), or when the application-level keepalive heartbeat timer expires.

### Q8: What is the difference between network byte order and host byte order?
**A:** Network byte order is strictly Big-Endian (most significant byte at the lowest memory address). Host byte order depends on CPU architecture (x86/x64 is Little-Endian). Java's `DataOutputStream` and `ByteBuffer` default to Big-Endian, matching network byte order.

---

## 2. TCP vs. UDP & Application-Layer Framing

### Q9: Why does TCP require application-layer framing while UDP does not?
**A:** TCP is a byte-stream protocol; it has no concept of message boundaries. Multiple small `write()` calls may be coalesced into one TCP segment (Nagle's algorithm), or one large write may be split across segments. The application must enforce framing. UDP is datagram-oriented; each `receive()` returns exactly one discrete datagram.

### Q10: How does MeshTalk implement framing?
**A:** Using a length-prefixed format: `[4-byte length][1-byte type][payload]`. The reader reads 4 bytes with `readInt()`, validates the length, and calls `readFully()` to read the exact payload bytes without truncation.

### Q11: What is the vulnerability of delimiter-based framing (e.g. newline `\n`)?
**A:** If binary data (such as an encrypted file chunk or image) contains the byte value `0x0A` (`\n`), the receiver prematurely splits the frame, causing stream corruption unless expensive byte-stuffing/escaping is used.

### Q12: Compare TCP and UDP across reliability, ordering, header overhead, and speed.
**A:**
- **TCP:** Connection-oriented, guaranteed reliable delivery, strict byte ordering, flow/congestion control, minimum 20-byte header, higher latency.
- **UDP:** Connectionless, best-effort (unreliable), no ordering, no flow control, fixed 8-byte header, minimal latency.

### Q13: What does UDP checksum cover?
**A:** The UDP header, the UDP payload, and a 12-byte IPv4 pseudo-header (Source IP, Destination IP, zero byte, Protocol number 17, and UDP length).

---

## 3. Multithreading, Concurrency & Thread Pools

### Q14: Explain the three server concurrency models implemented in MeshTalk.
**A:**
1. **SINGLE:** Handles one client sequentially. The accept loop blocks until the connected client disconnects, causing head-of-line blocking for all others.
2. **THREAD_PER_CLIENT:** Spawns a new `Thread` for every connection. Low latency at low load, but degrades rapidly due to memory consumption and context switching at high client counts.
3. **THREAD_POOL:** Reuses a fixed pool of pre-spawned worker threads (`ExecutorService`). Tasks queue in a `LinkedBlockingQueue`, bounding CPU and memory usage.

### Q15: Why is `ConcurrentHashMap` preferred over `Hashtable` or `Collections.synchronizedMap`?
**A:** `Hashtable` and `synchronizedMap` lock the entire table on every read and write. `ConcurrentHashMap` uses lock-striping and lock-free volatile reads, allowing multiple threads to broadcast and read online sessions simultaneously without contention.

### Q16: What is a Race Condition? How did you demonstrate and fix it?
**A:** A race condition occurs when concurrent threads execute non-atomic read-modify-write operations on shared mutable state (e.g., `count++` consists of 3 bytecodes: read, add 1, write). In `ConcurrencyDemo`, 8 threads incrementing an unsafe `int` lose thousands of updates. The fix uses `AtomicInteger` which leverages hardware Compare-And-Swap (CAS) instructions to guarantee atomicity without lock overhead.

### Q17: What is a Deadlock? How did you demonstrate and fix it?
**A:** Deadlock occurs when two or more threads are permanently blocked, each holding a lock the other needs. Demonstrated by Thread 1 acquiring `Lock(A)` then trying `Lock(B)`, while Thread 2 acquires `Lock(B)` then tries `Lock(A)` (Circular Wait). The fix enforces **Global Lock Ordering**—both threads must acquire `Lock(A)` before `Lock(B)`.

### Q18: What are the four Coffman conditions required for a deadlock?
**A:**
1. Mutual Exclusion
2. Hold and Wait
3. No Preemption
4. Circular Wait (breaking this via lock hierarchy eliminates deadlocks).

---

## 4. Reliable Data Transfer (RDT) over UDP

### Q19: What is the custom RDT packet header layout in MeshTalk?
**A:** 17 bytes total: `type` (1B), `seqNo` (4B), `ackNo` (4B), `windowSize` (2B), `length` (2B), `checksum` (4B CRC32).

### Q20: How does Stop-and-Wait differ from Pipelined protocols (GBN and SR)?
**A:** Stop-and-Wait has a window of 1: it sends 1 packet and stops until the ACK arrives, resulting in very low channel utilization ($U = \frac{L/R}{RTT + L/R}$). Pipelined protocols allow up to $N$ unacknowledged packets in flight, increasing throughput up to $N$-fold.

### Q21: What is the core difference between Go-Back-N and Selective Repeat?
**A:**
- **Go-Back-N (GBN):** Receiver accepts packets strictly in order and uses cumulative ACKs; any out-of-order packet is discarded. On timeout, sender retransmits *all* unacknowledged packets starting from `base`.
- **Selective Repeat (SR):** Receiver buffers out-of-order packets that fall within the receive window and acknowledges each packet individually. Sender maintains an individual timer per packet and retransmits *only* the specific timed-out packet.

### Q22: What is the maximum window size allowed in Selective Repeat with sequence number space of size $M$?
**A:** $N \le \frac{M}{2}$. If the window is larger than half the sequence number space, the receiver cannot distinguish between a duplicate packet from the previous window and a new packet from the next window.

### Q23: How does MeshTalk compute Retransmission Timeout (RTO)?
**A:** Using RFC 6298 EWMA formulas:
$$\text{EstimatedRTT} = 0.875 \cdot \text{EstimatedRTT} + 0.125 \cdot \text{SampleRTT}$$
$$\text{DevRTT} = 0.75 \cdot \text{DevRTT} + 0.25 \cdot |\text{SampleRTT} - \text{EstimatedRTT}|$$
$$\text{RTO} = \text{EstimatedRTT} + 4 \cdot \text{DevRTT}$$
Clamped between 200 ms and 10000 ms.

### Q24: What is Karn's Algorithm?
**A:** Karn's algorithm states:
1. Do not measure RTT for retransmitted packets (ambiguity of whether ACK belongs to original or retransmitted copy).
2. On timeout, double the RTO (exponential backoff) until a packet is acknowledged without retransmission.

### Q25: How does Fast Retransmit work?
**A:** If 3 duplicate ACKs for the same sequence number are received, the sender assumes the packet immediately following that ACK number was lost rather than delayed, and retransmits it immediately without waiting for the timer to expire.

### Q26: Explain AIMD Congestion Control.
**A:** Additive Increase, Multiplicative Decrease:
- **Additive Increase:** Increase congestion window by 1 MSS per RTT during congestion avoidance.
- **Multiplicative Decrease:** Halve the congestion window upon packet loss (timeout or triple duplicate ACK).

### Q27: What is the difference between TCP Tahoe and TCP Reno?
**A:**
- **Tahoe:** On both timeout and triple duplicate ACK, sets `ssthresh = cwnd / 2` and resets `cwnd = 1` MSS (re-enters Slow Start).
- **Reno:** On triple duplicate ACK, enters **Fast Recovery**: sets `ssthresh = cwnd / 2`, `cwnd = ssthresh + 3`, and avoids dropping `cwnd` to 1. On timeout, it drops `cwnd` to 1.

---

## 5. Network Layer: Subnetting, IPv4 & Routing

### Q28: What is CIDR notation?
**A:** Classless Inter-Domain Routing notation represents an IP address followed by a slash and the prefix length (e.g., `192.168.1.0/24`), indicating that the first 24 bits represent the network prefix and the remaining 8 bits represent host identifiers.

### Q29: How many usable host addresses exist in a `/26` subnet?
**A:** Total addresses = $2^{32 - 26} = 2^6 = 64$. Usable host addresses = $64 - 2 = 62$ (subtracting network ID and broadcast address).

### Q30: What is VLSM and how does your planner work?
**A:** Variable Length Subnet Masking allows subnets of different sizes to be carved from the same parent block to minimize IP address waste. MeshTalk's `VlsmPlanner` sorts requirements by required hosts descending (greedy approach) and allocates the smallest power-of-2 block that satisfies each requirement.

### Q31: How is the IPv4 One's Complement Checksum computed?
**A:**
1. Group header bytes into 16-bit words.
2. Sum all 16-bit words using 32-bit arithmetic.
3. Add carry bits back to the lower 16 bits until the sum fits in 16 bits.
4. Take the bitwise NOT (one's complement) of the sum.
5. On verification, summing all words including the checksum yields `0xFFFF`.

### Q32: What fields in the IPv4 header support fragmentation?
**A:**
1. **Identification (16 bits):** Uniquely identifies fragments originating from the same parent datagram.
2. **Flags (3 bits):** Bit 1 is DF (Don't Fragment); Bit 2 is MF (More Fragments: 1 if more fragments follow, 0 for last fragment).
3. **Fragment Offset (13 bits):** Specifies the data offset relative to the original unfragmented payload in units of **8-byte (64-bit)** blocks.

### Q33: Why is fragment offset measured in 8-byte units?
**A:** The Fragment Offset field is 13 bits ($2^{13} = 8192$ values), but the maximum IP packet size is 65,535 bytes. Multiplying by 8 allows 13 bits to span $8192 \times 8 = 65,536$ bytes.

### Q34: What is the difference between Link-State and Distance-Vector routing?
**A:**
- **Link-State (Dijkstra):** Global knowledge. Every router has the complete topology graph and independently computes shortest paths. Convergence is fast, but messaging overhead is higher.
- **Distance-Vector (Bellman-Ford):** Distributed, decentralized knowledge. Routers know only link costs to immediate neighbors and exchange distance vectors with neighbors. Convergence is slower and susceptible to routing loops.

### Q35: What is the Count-to-Infinity problem and how does Poison Reverse fix it?
**A:** When a link fails, two routers routing through each other continue updating each other with incrementally higher costs ($1, 2, 3...$) until reaching infinity. **Split Horizon with Poison Reverse** fixes this: if Router A routes to destination C via Router B, A advertises its distance to C as $\infty$ to B, preventing B from ever attempting to route back through A.
