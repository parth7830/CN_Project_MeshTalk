# MeshTalk Engineering Decision Log (ADR)

## ADR-01: Length-Prefixed Framing vs. Delimiter-Based Framing
- **Decision:** Use a 4-byte big-endian integer length prefix: `[4-byte Length][1-byte Type][Payload]`.
- **Rationale:** Delimiter-based framing (e.g., newline `\n` or zero byte `\0`) requires byte-stuffing/escaping when binary payloads (like raw file chunks or compressed images) contain the delimiter character. A length prefix enables constant-time memory pre-allocation and direct buffer streaming using `DataInputStream.readFully()`.

## ADR-02: ConcurrentHashMap for Online User Registry
- **Decision:** Use `ConcurrentHashMap<String, ClientSession>` for the global online user directory.
- **Rationale:** Chat broadcasting is a read-heavy operation. `ConcurrentHashMap` allows multiple threads to iterate and read sessions concurrently without blocking, while atomic operations (`putIfAbsent`, `remove`) prevent duplicate usernames and race conditions during simultaneous logins.

## ADR-03: CRC32 vs. 16-bit Internet Checksum in RDT
- **Decision:** Use 32-bit CRC32 for the custom RDT UDP packet header.
- **Rationale:** While IPv4 uses the 16-bit One's Complement Internet Checksum (which we implement in `Ipv4Header`), One's Complement sum is vulnerable to transposed 16-bit words. CRC32 provides vastly superior error-detection polynomials for random bit errors, burst flips, and frame truncations simulated in `LossyChannel`.

## ADR-04: SHA-256 for End-to-End File Verification
- **Decision:** Transmit the full SHA-256 hex digest in the TCP `FILE_END` and RDT `FIN` packets.
- **Rationale:** Packet-level checksums (CRC32/TCP checksum) protect individual datagrams from corruption in transit, but do not protect against reordering errors, missing chunks, or race conditions during multi-packet reassembly. End-to-end cryptographic SHA-256 provides definitive mathematical proof that the reassembled file matches the original bit-for-bit.

## ADR-05: Three Server Modes Switchable at Runtime
- **Decision:** Implement `SINGLE`, `THREAD_PER_CLIENT`, and `THREAD_POOL` as selectable options via CLI flags and GUI toggles.
- **Rationale:** Academic PBL evaluation requires demonstrable proof of *why* thread pools are used. Having the baseline single-threaded bottleneck and unbounded thread-per-client available side-by-side allows live measurement and demonstration of head-of-line blocking and thread exhaustion.

## ADR-06: Port Number Allocation
- **Decision:**
  - TCP Chat & File Server: `5000`
  - UDP Status & Echo Ping: `5001`
  - UDP RDT File Transfer: `5002`
  - Dynamic Ephemeral: Auto-allocated (`0`) during automated unit tests to prevent `java.net.BindException` during rapid test runs.
- **Rationale:** Segregates protocols into predictable well-known ports for Wireshark filtering while ensuring test suite isolation.
