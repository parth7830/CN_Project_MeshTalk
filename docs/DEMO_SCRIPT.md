# MeshTalk Live Demonstration Script (5 to 7 Minutes)

**Examiner:** Dr. Meenal Jain  
**Course:** Computer Networks (24B11CS311), JIIT Noida  
**Presenter Team:** Parth Sharma, Saurabh Kumar Chaurasia, Rajan Palta  

---

## Minute 0:00 – 1:00: Introduction & Architecture
- **Speaker:** Parth Sharma
- **Action:** Open terminal and run:
  ```cmd
  scripts\run-demo.bat
  ```
- **Speaking Points:**
  - *"Good afternoon Ma'am. We present MeshTalk, a client-server network communication system built strictly using raw Java sockets (`java.net`, `java.nio`, `java.util.concurrent`) with zero external networking frameworks."*
  - *"MeshTalk covers all key layers of the syllabus: length-prefixed stream framing, three multithreaded server architectures, custom RDT over UDP with congestion control, and network layer tools."*
  - Point to the console output: *"Notice our automated test runner just executed 7 stages covering all modules with 100% PASS."*

---

## Minute 1:00 – 2:30: Server Dashboard & Concurrency
- **Speaker:** Parth Sharma
- **Action:** Launch the GUI:
  ```bash
  mvn javafx:run
  ```
  Navigate to **Tab 2: Server Dashboard** and click **Start Server**.
- **Speaking Points:**
  - *"Here is our live Server Dashboard. Notice the architecture toggle: SINGLE, THREAD_PER_CLIENT, and THREAD_POOL."*
  - *"In our performance report, SINGLE collapses at 25 concurrent connections due to head-of-line blocking. THREAD_PER_CLIENT consumes excess native stack memory. THREAD_POOL with a fixed pool of 16 threads maintains peak throughput of over 6000 messages per second."*
- **Action:** Switch to **Tab 6: Concurrency Lab**.
  - Click **Run Race Condition Demo**: Show how unsynchronized shared state loses thousands of updates, while `AtomicInteger` with hardware CAS achieves the exact total.
  - Click **Simulate Deadlock** then **Deadlock Fixed (Lock Ordering)**: Explain how breaking the circular wait condition guarantees deadlock-free execution.

---

## Minute 2:30 – 4:00: Reliable Data Transfer (RDT) Lab over UDP
- **Speaker:** Saurabh Kumar Chaurasia
- **Action:** Switch to **Tab 3: RDT over UDP Lab**.
- **Speaking Points:**
  - *"Now to the centerpiece of our transport-layer work: Reliable Data Transfer over raw UDP datagrams."*
  - *"We designed a custom 17-byte binary header containing packet type, 32-bit sequence numbers, ACKs, receiver window size, and CRC32 checksums."*
- **Live Experiment:**
  - Set **Protocol** to `Selective Repeat`.
  - Set **Packet Loss %** to `10%` using the slider.
  - Set **Congestion Control** to `Reno (AIMD)`.
  - Click **Run RDT Transfer Simulation**.
- **Speaking Points:**
  - *"Notice the live CWND chart demonstrating exponential growth during Slow Start and linear growth during Congestion Avoidance."*
  - *"Despite 10% software-simulated packet drops in our `LossyChannel`, the sender detects loss via timeout, performs selective retransmission, and reports SHA-256 VERIFIED at the receiver."*

---

## Minute 4:00 – 5:30: Network Tools & Routing Simulation
- **Speaker:** Rajan Palta
- **Action:** Switch to **Tab 5: Network Tools**.
- **Subnet Calculator:**
  - Input: `192.168.1.130/26` → Click **Calculate**.
  - *"The tool parses CIDR prefixes, computes network ID `192.168.1.128`, broadcast `192.168.1.191`, 62 usable hosts, and displays the binary bitmask breakdown."*
- **IPv4 Fragmentation:**
  - Payload: `4000`, MTU: `1500` → Click **Fragment & Reassemble**.
  - *"Notice how the 4000-byte datagram is split into three fragments of 1480, 1480, and 1040 bytes. Offsets are formatted in 8-byte units (0, 185, 370) with More Fragments (MF) flag set correctly, and reassembly matches the original bit-for-bit."*
- **Routing Algorithms:**
  - Click **Run Dijkstra (Link State)** then **Run Bellman-Ford (Distance Vector)** on the Kurose textbook graph.
  - Click **Simulate Count-To-Infinity** and **Count-To-Infinity + Poison Reverse**.
  - *"Notice how severing link B-C causes standard Distance Vector to diverge towards infinity, while Split Horizon with Poison Reverse immediately converges."*

---

## Minute 5:30 – 6:30: Protocol Inspector & Conclusion
- **Speaker:** Rajan Palta & Team
- **Action:** Switch to **Tab 4: Protocol Inspector**.
- **Speaking Points:**
  - *"Finally, our Protocol Inspector provides an in-app Wireshark view, decoding each length-prefixed frame and RDT datagram directly on the screen."*
  - *"To conclude: every concept in the syllabus is not only implemented, but visible, measurable, and verified through our automated test suite."*

---

## Fallback Plan (If JavaFX Fails to Render on Target Machine)
1. Run the headless CLI client:
   ```cmd
   scripts\run-client.bat --host localhost --port 5000 --user Examiner
   ```
2. Type `/subnet 192.168.1.130/26` to run the subnet calculator in the console.
3. Type `/route` to run the routing simulation.
4. Type `/concurrency` to run the race condition and deadlock demos.
5. Type `/rdt` to execute the UDP file transfer with 10% packet loss.
