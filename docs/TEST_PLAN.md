# MeshTalk Comprehensive Test Plan & Verification Matrix

## 1. Test Architecture & Methodology

All automated tests in MeshTalk are implemented with **JUnit 5 Jupiter** without third-party mocking libraries, interacting directly with real local socket endpoints (`127.0.0.1`) and real multithreaded workers.

---

## 2. Test Execution Matrix

| Test ID | Module | Test Description | Input / Condition | Expected Result | Status |
|---|---|---|---|---|---|
| **TC-01** | `server` | ServerSocket startup & shutdown | Port dynamically assigned | Clean bind without address collision; clean release | **PASS** |
| **TC-02** | `server` | Unique Username Registration | First user registers "Alice" | Server returns `LOGIN_OK` | **PASS** |
| **TC-03** | `server` | Duplicate Username Rejection | Second client requests "Alice" | Server returns `LOGIN_FAIL`, socket closed | **PASS** |
| **TC-04** | `server` | Multi-Client Public Broadcast | Alice sends public message | Bob & Charlie receive relay with sender name | **PASS** |
| **TC-05** | `server` | Unicast Private Messaging | Alice sends PM to Bob | Bob receives; Charlie does NOT receive | **PASS** |
| **TC-06** | `server` | TCP Chunked File Transfer | 8 KB binary random payload | Relayed in 4KB chunks; SHA-256 matches exactly | **PASS** |
| **TC-07** | `server` | High Concurrent Connection Load | 25 concurrent persistent clients | All 25 connect, register, and disconnect cleanly | **PASS** |
| **TC-08** | `server` | Malformed Frame Protection | Raw socket sends data before LOGIN | Server drops frame and terminates connection | **PASS** |
| **TC-09** | `server` | Heartbeat Timeout & Eviction | Client goes silent > 15s | Server reaper cleans session, releases socket | **PASS** |
| **TC-10** | `server` | Race Condition Reproduction | 8 threads increment shared counter | Unsafe counter yields lost updates ($< 80000$) | **PASS** |
| **TC-11** | `server` | Race Condition Hardware Fix | AtomicInteger with hardware CAS | Exactly 80000 updates, zero lost updates | **PASS** |
| **TC-12** | `server` | Deadlock Detection & Prevention| Opposite lock order vs strict order | Unordered detects hang; ordered finishes cleanly | **PASS** |
| **TC-13** | `rdt` | Custom RDT Packet Serialization | 17-byte header + binary payload | Byte buffer serialize/deserialize round-trip | **PASS** |
| **TC-14** | `rdt` | CRC32 Corruption Detection | Bit flipped in payload byte | `verifyChecksum()` returns false; packet dropped | **PASS** |
| **TC-15** | `rdt` | Adaptive RTO (RFC 6298 EWMA) | SampleRTT values fed to estimator | RTO dynamically adjusts; doubles on timeout | **PASS** |
| **TC-16** | `rdt` | TCP Congestion Control (Tahoe) | ACKs and timeout events | Slow start doubles cwnd; timeout resets cwnd=1 | **PASS** |
| **TC-17** | `rdt` | TCP Congestion Control (Reno) | Triple duplicate ACKs | Enters Fast Recovery: `cwnd = ssthresh + 3` | **PASS** |
| **TC-18** | `rdt` | Zero-Loss Transfer (GBN) | 4 KB file over lossless UDP | Completed with 0 retransmissions; SHA-256 MATCH | **PASS** |
| **TC-19** | `rdt` | Lossy Transfer (Selective Repeat)| 16 KB file with 25% simulated loss| Completed with retransmissions; SHA-256 MATCH | **PASS** |
| **TC-20** | `nettools`| CIDR Subnet Calculation | `192.168.1.130/26` | Network 192.168.1.128, Bcast 192.168.1.191, Hosts 62 | **PASS** |
| **TC-21** | `nettools`| Classful IP Classification | `10.50.100.1/8` | Class A, RFC 1918 Private = true | **PASS** |
| **TC-22** | `nettools`| Subnet Mask Validation | Invalid non-contiguous mask | Throws `IllegalArgumentException` | **PASS** |
| **TC-23** | `nettools`| VLSM Hierarchical Allocation | Parent `192.168.1.0/24`, hosts 60,25,10| 3 non-overlapping subnets carved correctly | **PASS** |
| **TC-24** | `nettools`| RFC 1071 Internet Checksum | 20-byte IPv4 header | Checksum computes and verifies to 0xFFFF | **PASS** |
| **TC-25** | `nettools`| Path MTU Fragmentation | 4000-byte payload with MTU 1500 | Exactly 3 fragments created (1480, 1480, 1040) | **PASS** |
| **TC-26** | `nettools`| Fragment Reassembly | Out-of-order fragment delivery | Reassembled payload matches original bit-for-bit | **PASS** |
| **TC-27** | `nettools`| Dijkstra Shortest Path | Kurose-Ross 6-node topology | Optimal shortest path tree matching textbook | **PASS** |
| **TC-28** | `nettools`| Distance Vector (Bellman-Ford) | Kurose-Ross 6-node topology | Iterative rounds converge to identical costs | **PASS** |
| **TC-29** | `nettools`| Count-to-Infinity Simulation | Sever link B-C in 3-node graph | Distance vector diverges towards infinity | **PASS** |
| **TC-30** | `nettools`| Poison Reverse Convergence | Sever link B-C with Poison Reverse | Divergence halted immediately; cost = INFINITY | **PASS** |

---

## 3. Automated Verification Command
To reproduce all 30 verification cases in a single run:
```bash
mvn clean test
```
All tests must report **BUILD SUCCESS** with zero failures or errors.
