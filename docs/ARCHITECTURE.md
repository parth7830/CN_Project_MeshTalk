# MeshTalk System Architecture

## 1. High-Level Layered Architecture

MeshTalk maps directly to the classical ISO/OSI and TCP/IP protocol stacks, demonstrating how modern network protocols operate from raw sockets up to the application interface.

```mermaid
graph TD
    UI["JavaFX Dashboard / Terminal CLI"]
    APP["Application Layer: Framing, Session Management, Chat & File Relays"]
    TRANS_TCP["Transport Layer (TCP): Raw Socket, Thread Pool, Flow Control"]
    TRANS_UDP["Transport Layer (UDP): Custom RDT (S&W, GBN, SR) + AIMD Congestion Control"]
    NET_SIM["Network Layer Simulation: IPv4 Headers, MTU Fragmentation, Subnetting, Dijkstra/DV Routing"]
    IMPAIR["LossyChannel: Simulated Loss, Corruption, Duplication & Delay"]
    KERNEL["OS Kernel Sockets (Loopback / LAN Physical NIC)"]

    UI --> APP
    APP --> TRANS_TCP
    APP --> TRANS_UDP
    TRANS_UDP --> IMPAIR
    IMPAIR --> KERNEL
    TRANS_TCP --> KERNEL
    UI --> NET_SIM
```

---

## 2. Server Concurrency Models

MeshTalk implements three distinct server concurrency architectures to measure performance and resource limits:

```mermaid
graph LR
    subgraph SINGLE [1. Single-Threaded]
        S_Accept[ServerSocket.accept] --> S_Process[Process Client Sequentially]
    end

    subgraph THREAD_PER_CLIENT [2. Thread Per Client]
        T_Accept[ServerSocket.accept] --> T_Spawn[new Thread for each client]
    end

    subgraph THREAD_POOL [3. Thread Pool - Default]
        P_Accept[ServerSocket.accept] --> P_Queue[LinkedBlockingQueue]
        P_Queue --> W1[Worker Thread 1]
        P_Queue --> W2[Worker Thread 2]
        P_Queue --> WN[Worker Thread N]
    end
```

---

## 3. TCP Connection & Authentication Sequence

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant ServerSocket
    participant WorkerThread
    participant Registry as ConcurrentHashMap

    Client->>ServerSocket: TCP 3-Way Handshake (SYN, SYN-ACK, ACK)
    ServerSocket->>WorkerThread: Hand off accepted Socket
    Client->>WorkerThread: [Frame] LOGIN (username)
    WorkerThread->>Registry: putIfAbsent(username, Session)
    alt Username Unique
        Registry-->>WorkerThread: Success
        WorkerThread-->>Client: [Frame] LOGIN_OK
        WorkerThread->>Client: [Frame] HISTORY (recent messages)
        WorkerThread-->>Registry: Broadcast JOIN to all clients
    else Duplicate Username
        Registry-->>WorkerThread: Key already exists
        WorkerThread-->>Client: [Frame] LOGIN_FAIL (reason)
        WorkerThread->>Client: Close Socket
    end
```

---

## 4. Reliable Data Transfer (RDT) over UDP Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Sender as RDT Sender
    participant Channel as LossyChannel
    participant Receiver as RDT Receiver

    Note over Sender,Receiver: 1. Connection Handshake
    Sender->>Channel: RDT Packet [SYN, seq=0]
    Channel->>Receiver: Deliver SYN
    Receiver->>Sender: RDT Packet [SYN_ACK, ack=0]
    Sender->>Receiver: RDT Packet [ACK]

    Note over Sender,Receiver: 2. Pipelined Data Transfer with Packet Loss
    Sender->>Channel: DATA (seq=0, payload)
    Channel->>Receiver: Delivered
    Receiver->>Sender: ACK (ack=0)

    Sender->>Channel: DATA (seq=1, payload)
    Channel--xReceiver: DROPPED BY LOSSY CHANNEL (Simulated Loss)
    
    Sender->>Channel: DATA (seq=2, payload)
    Channel->>Receiver: Delivered (Out of order)

    alt Go-Back-N (GBN)
        Receiver->>Sender: ACK (ack=0) [Duplicate ACK!]
        Note over Sender: Base Timeout fires for seq=1
        Sender->>Channel: Retransmit seq=1 & seq=2
    else Selective Repeat (SR)
        Receiver->>Sender: ACK (ack=2) [Buffered seq=2]
        Note over Sender: Individual Timer fires for seq=1 only
        Sender->>Channel: Retransmit seq=1 only
        Channel->>Receiver: Delivered seq=1
        Note over Receiver: In-order delivery to file buffer
    end

    Note over Sender,Receiver: 3. Teardown & Cryptographic Verification
    Sender->>Receiver: RDT Packet [FIN, payload=SHA-256 Hash]
    Receiver->>Receiver: Compare SHA-256 with received byte stream
    Receiver-->>Sender: RDT Packet [FIN_ACK]
    Note over Receiver: Display "VERIFIED (SHA-256 MATCH)"
```

---

## 5. Threading and State Safety

- **Session Registry**: `ConcurrentHashMap<String, ClientSession>` ensures $O(1)$ lock-free reads during broadcast, with thread-safe atomic put/delete operations.
- **Atomic Counters**: All metrics (`connectedClients`, `totalMessages`, `totalBytes`, `activeThreads`) use `java.util.concurrent.atomic.AtomicLong` and `AtomicInteger` for lock-free hardware CAS operations.
- **FrameWriter**: Synchronizes internally on the socket's `DataOutputStream` monitor lock, preventing frame corruption when multiple background threads write to the same client simultaneously.
- **Heartbeat Reaper**: A dedicated daemon thread scans online sessions every 5 seconds and purges sockets that have been silent for more than 15 seconds.
