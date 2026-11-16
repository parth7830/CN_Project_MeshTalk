# MeshTalk: Multithreaded Client-Server Communication System

> **A Comprehensive Computer Networks Project (Course: 24B11CS311)**  
> **Institution:** Jaypee Institute of Information Technology (JIIT), Noida  
> **Faculty Guide:** Dr. Meenal Jain  
> **Team Members & Responsibilities:**  
> - **Parth Sharma:** Server architecture, thread pools, application framing, integration  
> - **Saurabh Kumar Chaurasia:** TCP/UDP clients, Reliable Data Transfer (RDT) over UDP, flow/congestion control  
> - **Rajan Palta:** Network-layer toolkit (Subnetting, VLSM, IPv4 header, Routing algorithms), Wireshark capture analysis, test suite, documentation  

---

## 1. Executive Summary

**MeshTalk** is a high-performance network communication system and interactive educational laboratory built strictly using **pure Java standard library sockets** (`java.net`, `java.nio`, `java.util.concurrent`). It incorporates:

1. **Multithreaded TCP Server**: Real-time broadcast and private chat, file relay with cryptographic SHA-256 verification, and comparative runtime benchmarking of three concurrency architectures:
   - `SINGLE`: Sequential single-thread baseline
   - `THREAD_PER_CLIENT`: Unbounded thread-per-connection model
   - `THREAD_POOL`: Bounded worker thread pool with task queuing
2. **Reliable Data Transfer (RDT) over UDP**: A custom transport protocol built on top of UDP with a 17-byte custom binary header, sliding window protocols (**Stop-and-Wait**, **Go-Back-N**, and **Selective Repeat**), adaptive retransmission timeouts (RFC 6298 EWMA formulas), and TCP congestion control (**Tahoe** & **Reno** AIMD).
3. **LossyChannel Impairment Engine**: Configurable software simulation of packet drops (0–50%), bit corruption, duplicate packet generation, and packet jitter/delay.
4. **Network Layer Toolkit**:
   - Classful & CIDR Subnet Calculator with binary breakdown
   - VLSM (Variable Length Subnet Mask) greedy allocation planner
   - 20-byte IPv4 Header parser with RFC 1071 One's Complement Internet Checksum
   - Path MTU IP Fragmentation and out-of-order reassembly engine
   - Link-State (**Dijkstra**) and Distance-Vector (**Bellman-Ford**) routing simulation with Count-to-Infinity demonstration and Poison Reverse fix.
5. **Interactive JavaFX Dashboard**: Dark-themed monitoring station with live throughput charts, CWND visualization, and a Wireshark-style Protocol Inspector.
6. **Full Terminal CLI & Automated Demo Mode**: Complete feature parity from the command line for headless environments.

---

## 2. Syllabus Mapping Checklist

| Syllabus Topic | Implementation Location | Interactive UI / Demo |
|---|---|---|
| **Client-Server Architecture** | `meshtalk.server.MeshTalkServer`, `meshtalk.client.TcpClient` | Tab 1: Live Chat & Tab 2: Server Dashboard |
| **Stream Framing** | `meshtalk.common.Frame`, `FrameWriter` | Tab 4: Protocol Inspector |
| **Socket Options & Timeout** | `Socket.setSoTimeout()`, `ServerSocket.setReuseAddress()` | Server accept and client read loops |
| **Multithreading Models** | `ServerMode` (SINGLE, THREAD_PER_CLIENT, THREAD_POOL) | Tab 2 Architecture Switcher |
| **Concurrency Hazards** | `meshtalk.server.ConcurrencyDemo` (Race Condition & Deadlock) | Tab 6: Concurrency Lab |
| **UDP Sockets & Discovery** | `meshtalk.server.UdpService`, `meshtalk.client.UdpClient` | LAN Discovery & Echo Ping |
| **Sliding Window Protocols** | `StopAndWait`, `GbnSender`, `SelectiveRepeatSender` | Tab 3: RDT Lab |
| **Adaptive RTO** | `meshtalk.rdt.RttEstimator` (RFC 6298 EWMA) | Tab 3: RDT Live Charts |
| **Flow & Congestion Control**| `meshtalk.rdt.CongestionController` (AIMD, Tahoe, Reno) | Tab 3: CWND vs Time Chart |
| **Subnetting & CIDR** | `meshtalk.nettools.SubnetCalculator`, `VlsmPlanner` | Tab 5: Subnet & CIDR Tool |
| **IPv4 Header & Checksum** | `meshtalk.nettools.Ipv4Header` (RFC 791, RFC 1071) | Tab 5: IPv4 Header Visualizer |
| **IP Fragmentation** | `meshtalk.nettools.FragmentationDemo` | Tab 5: Fragmentation Demo |
| **Routing Algorithms** | `meshtalk.nettools.RoutingSimulator` (Dijkstra, Bellman-Ford) | Tab 5: Dijkstra & DV Visualizer |
| **Integrity Verification** | `meshtalk.common.ChecksumUtil` (CRC32, SHA-256) | File Transfer Completion Banner |

---

## 3. Quick Start

### Prerequisites
- **JDK 17+** installed and on `PATH`
- **Apache Maven 3.8+**

### Step 1: Build the Project
```bash
mvn clean package
```

### Step 2: Run the Automated Showcase (Demo Mode)
```cmd
scripts\run-demo.bat
```
*(On Linux/macOS: `./scripts/run-demo.sh`)*

### Step 3: Run the JavaFX GUI
```bash
mvn javafx:run
```

### Step 4: Run via Command Line (CLI)
- **Start the Server:**
  ```cmd
  scripts\run-server.bat --port 5000 --mode THREAD_POOL --pool-size 16
  ```
- **Start Client 1 (Alice):**
  ```cmd
  scripts\run-client.bat --host localhost --port 5000 --user Alice
  ```
- **Start Client 2 (Bob):**
  ```cmd
  scripts\run-client.bat --host localhost --port 5000 --user Bob
  ```

### Step 5: Run the Benchmark Suite
```cmd
scripts\run-bench.bat
```

---

## 4. Repository Structure

```
meshtalk/
├── pom.xml                                 # Maven Java 17 configuration
├── README.md                               # Project guide and overview
├── docs/
│   ├── ARCHITECTURE.md                     # System design & Mermaid diagrams
│   ├── PROTOCOL.md                         # Application & RDT protocol specifications
│   ├── WIRESHARK_GUIDE.md                  # Packet capture guide & filters
│   ├── TEST_PLAN.md                        # Verification test cases
│   ├── PERFORMANCE_REPORT.md               # Concurrency benchmark evaluation
│   ├── DECISIONS.md                        # Engineering decision log
│   ├── VIVA_QA.md                          # 50+ Viva examination questions & answers
│   └── DEMO_SCRIPT.md                      # 5-7 minute scripted presentation walkthrough
├── scripts/
│   ├── run-server.bat / .sh                # Start MeshTalk server
│   ├── run-client.bat / .sh                # Launch CLI client
│   ├── run-bench.bat / .sh                 # Run performance benchmark suite
│   └── run-demo.bat / .sh                  # Run end-to-end automated showcase
└── src/
    ├── main/java/meshtalk/
    │   ├── common/                         # Framing, protocol enums, CRC32, SHA-256
    │   ├── server/                         # ServerSocket, thread pool, client registry, UDP service
    │   ├── client/                         # TCP client, UDP discovery, interactive CLI
    │   ├── rdt/                            # Reliable UDP transfer (S&W, GBN, SR, AIMD)
    │   ├── nettools/                       # Subnet, VLSM, IPv4 header, fragmentation, routing
    │   ├── bench/                          # Automated benchmark harness and DemoMode
    │   └── ui/                             # JavaFX interactive dark-theme dashboard
    └── test/java/meshtalk/                 # Automated JUnit 5 test suite
```

---

## 5. License
MIT License — Academic PBL Submission for JIIT Noida.
