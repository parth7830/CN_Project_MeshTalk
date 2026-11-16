package meshtalk;

import meshtalk.common.ChecksumUtil;
import meshtalk.rdt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RdtTest {

    @Test
    public void testRdtPacketSerializationAndChecksum() {
        byte[] payload = "Reliable Data Transfer over UDP Packet Check".getBytes();
        RdtPacket pkt = RdtPacket.data(42, payload);

        assertTrue(pkt.verifyChecksum(), "Initial packet checksum must verify");

        byte[] serialized = pkt.toBytes();
        RdtPacket deserialized = RdtPacket.fromBytes(serialized);

        assertEquals(RdtPacket.Type.DATA, deserialized.getType());
        assertEquals(42, deserialized.getSeqNo());
        assertArrayEquals(payload, deserialized.getPayload());
        assertTrue(deserialized.verifyChecksum(), "Deserialized packet must verify");

        // Test corruption detection
        serialized[serialized.length - 1] ^= 0xFF; // Flip bits
        RdtPacket corrupted = RdtPacket.fromBytes(serialized);
        assertFalse(corrupted.verifyChecksum(), "Corrupted packet must fail checksum verification");
    }

    @Test
    public void testRttEstimator() {
        RttEstimator estimator = new RttEstimator();
        long initialRto = estimator.getRto();

        estimator.addSample(100);
        assertTrue(estimator.getRto() >= 100);

        estimator.addSample(120);
        estimator.addSample(80);

        long rtoBeforeTimeout = estimator.getRto();
        estimator.timeout();
        assertEquals(rtoBeforeTimeout * 2, estimator.getRto(), "Exponential backoff doubles RTO on timeout");
    }

    @Test
    public void testCongestionController() {
        // Test Tahoe
        CongestionController tahoe = new CongestionController(CongestionController.Mode.TAHOE);
        assertEquals(1.0, tahoe.getCwnd());

        // Slow start
        tahoe.onAck();
        assertEquals(2.0, tahoe.getCwnd());
        tahoe.onAck();
        assertEquals(3.0, tahoe.getCwnd());

        // Timeout resets cwnd to 1
        tahoe.onTimeout();
        assertEquals(1.0, tahoe.getCwnd());

        // Test Reno Fast Recovery
        CongestionController reno = new CongestionController(CongestionController.Mode.RENO);
        for (int i = 0; i < 10; i++) reno.onAck();
        double cwndBeforeDup = reno.getCwnd();
        reno.onTripleDupAck();
        assertTrue(reno.getCwnd() > 1.0, "Reno fast recovery sets cwnd = ssthresh + 3, not 1");
    }

    @Test
    public void testRdtTransferZeroLoss(@TempDir Path tempDir) throws Exception {
        File src = tempDir.resolve("src_zero_loss.bin").toFile();
        File dst = tempDir.resolve("dst_zero_loss.bin").toFile();

        byte[] content = new byte[4096];
        new java.util.Random().nextBytes(content);
        Files.write(src.toPath(), content);

        RdtFileTransfer.TransferResult result = RdtFileTransfer.runLocalTransfer(
                src, dst,
                RdtFileTransfer.ProtocolType.GO_BACK_N, 4,
                0.0, 0.0, 0,
                CongestionController.Mode.RENO,
                RdtEventListener.NOOP
        );

        assertTrue(result.success, "Transfer must succeed");
        assertTrue(result.verified, "SHA-256 hash must match");
        assertEquals(0, result.retransmissions, "Zero loss should have zero retransmissions");
    }

    @Test
    public void testRdtTransferWithLoss(@TempDir Path tempDir) throws Exception {
        File src = tempDir.resolve("src_loss.bin").toFile();
        File dst = tempDir.resolve("dst_loss.bin").toFile();

        byte[] content = new byte[8192];
        new java.util.Random().nextBytes(content);
        Files.write(src.toPath(), content);

        // 10% simulated packet loss with Selective Repeat
        RdtFileTransfer.TransferResult result = RdtFileTransfer.runLocalTransfer(
                src, dst,
                RdtFileTransfer.ProtocolType.SELECTIVE_REPEAT, 8,
                0.10, 0.0, 2,
                CongestionController.Mode.RENO,
                RdtEventListener.NOOP
        );

        assertTrue(result.success);
        assertTrue(result.verified);
        assertTrue(result.retransmissions >= 0);
    }
}
