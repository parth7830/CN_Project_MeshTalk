# MeshTalk Wireshark Packet Capture & Analysis Guide

## 1. Environment Setup

To capture localhost traffic on Windows, Wireshark relies on the **Npcap Loopback Adapter**:
1. During Wireshark installation, ensure **"Install Npcap with support for raw 802.11 traffic and loopback support"** is checked.
2. Launch Wireshark as Administrator.
3. In the interface list, select **`Npcap Loopback Adapter`** (or `Adapter for loopback traffic capture`).
4. On Linux/macOS, select interface `lo` or `lo0`.

---

## 2. Essential Display Filters for MeshTalk

| Purpose | Wireshark Display Filter | What to Observe |
|---|---|---|
| **All MeshTalk TCP Traffic** | `tcp.port == 5000` | Full TCP connection lifecycle, framing, chat & file relays |
| **All MeshTalk UDP & RDT** | `udp.port == 5001 || udp.port == 5002` | RDT sliding window transfers, echo pings, and LAN discovery |
| **TCP 3-Way Handshake** | `tcp.flags.syn == 1` | SYN and SYN-ACK exchange during client login |
| **TCP Connection Teardown** | `tcp.flags.fin == 1` | FIN and FIN-ACK exchange during clean logout |
| **TCP Window Updates** | `tcp.window_size_value` | Receiver advertised window dynamics under load |
| **TCP Retransmissions** | `tcp.analysis.retransmission` | Kernel TCP retransmissions under network congestion |
| **Duplicate ACKs** | `tcp.analysis.duplicate_ack` | Duplicate ACKs triggering fast retransmit |
| **Filter by Client User** | `tcp contains "Alice"` | Pinpoint frames carrying specific usernames or messages |

---

## 3. Demonstration & Screenshot Checklist

### 1. TCP Three-Way Handshake (RFC 793)
- **Filter:** `tcp.port == 5000 and tcp.flags.syn == 1`
- **Expected Sequence:**
  1. `Client → Server`: `[SYN]` Seq=0, Win=65535, MSS=65495
  2. `Server → Client`: `[SYN, ACK]` Seq=0, Ack=1, Win=65535, MSS=65495
  3. `Client → Server`: `[ACK]` Seq=1, Ack=1
- **Viva Discussion Point:** Notice how sequence numbers start at an Initial Sequence Number (ISN) and increment by 1 for SYN/FIN control flags, but by payload length for data segments.

### 2. Application-Layer Framing (Length-Prefixed Wire Format)
- **Filter:** `tcp.port == 5000 and tcp.len > 0`
- **Inspection Steps:**
  1. Expand the **Data** or **TCP Payload** section in the packet details pane.
  2. The first 4 bytes represent the Big-Endian 32-bit frame length. For example, `00 00 00 06` means 6 bytes follow.
  3. The 5th byte is the opcode: `01` (`LOGIN`), `10` (`MSG`), or `20` (`FILE_OFFER`).
  4. The subsequent bytes contain the UTF-8 payload.

### 3. Custom RDT Packet over UDP (17-byte Header)
- **Filter:** `udp.port == 5002`
- **Packet Byte Breakdown:**
  ```
  Byte 0       : Type (0x01 = DATA, 0x02 = ACK, 0x03 = SYN)
  Bytes 1–4    : 32-bit Sequence Number
  Bytes 5–8    : 32-bit Acknowledgement Number
  Bytes 9–10   : 16-bit Window Size
  Bytes 11–12  : 16-bit Payload Length
  Bytes 13–16  : 32-bit CRC32 Checksum
  Bytes 17+    : File payload chunk
  ```
- **Observation:** Notice how UDP provides no reordering, acknowledgment, or reliability at the OS layer; the application handles retransmissions, cumulative/selective ACKs, and duplicate detection.

---

## 4. Header Comparison: Textbook Theory vs. Wire Reality

| Header Field | Theoretical Purpose (Textbook) | Observed in Wireshark Capture |
|---|---|---|
| **Source Port** | Identifies sending process | Ephemeral port allocated by OS (e.g., 54321) |
| **Destination Port** | Identifies destination service | MeshTalk default TCP `5000` / UDP `5001` |
| **Sequence Number** | Byte stream offset index | Randomized 32-bit ISN; relative seq shown as 0, 1, ... |
| **Acknowledgment No** | Next expected byte | Confirms all bytes up to Ack-1 received |
| **Header Length (Data Offset)**| Specifies header size (5-15 32-bit words)| Usually `20 bytes` (5 words) or `32 bytes` with options |
| **Flags** | Control signals (SYN, ACK, FIN, RST, PSH, URG) | `[PSH, ACK]` for immediate application frame delivery |
| **Window Size** | Receiver advertised flow control buffer | Dynamically scales according to socket buffer fill |
| **Checksum** | Header + pseudo-header integrity verification | Offloaded to NIC hardware (may show "unverified" on loopback) |

---

## 5. Exporting Captures for Viva Examination
1. In Wireshark, navigate to **File → Export Specified Packets...**
2. Choose **All packets** or **Displayed** (filtered) packets.
3. Save as `meshtalk_capture.pcapng`.
4. To export plain text dissections: **File → Export Packet Dissections → As Plain Text...**
