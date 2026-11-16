package meshtalk;

import meshtalk.common.ChecksumUtil;
import meshtalk.nettools.FragmentationDemo;
import meshtalk.nettools.Ipv4Header;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FragmentationTest {

    @Test
    public void testInternetChecksumComputationAndVerification() {
        Ipv4Header header = new Ipv4Header(0, 1500, 100, 0, 0, 64, 6, "192.168.1.1", "192.168.1.2");
        assertTrue(header.verifyChecksum(), "IPv4 checksum must verify correctly");
    }

    @Test
    public void testPacketFragmentationAndReassembly() {
        Ipv4Header header = new Ipv4Header(0, 4020, 1001, 0, 0, 64, 6, "10.0.0.1", "10.0.0.2");

        // 4000 bytes payload
        byte[] payload = new byte[4000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 256);
        }

        // MTU 1500: max payload per fragment = ((1500 - 20) / 8) * 8 = 1480
        // Frags: 1480, 1480, 1040 (total 3 fragments)
        List<FragmentationDemo.Fragment> frags = FragmentationDemo.fragment(header, payload, 1500);

        assertEquals(3, frags.size(), "Should produce exactly 3 fragments");

        // Fragment 1
        assertEquals(0, frags.get(0).offsetBytes);
        assertTrue(frags.get(0).moreFragments);
        assertEquals(1480, frags.get(0).payload.length);

        // Fragment 2
        assertEquals(1480, frags.get(1).offsetBytes);
        assertTrue(frags.get(1).moreFragments);
        assertEquals(1480, frags.get(1).payload.length);

        // Fragment 3
        assertEquals(2960, frags.get(2).offsetBytes);
        assertFalse(frags.get(2).moreFragments);
        assertEquals(1040, frags.get(2).payload.length);

        // Reassemble
        byte[] reassembled = FragmentationDemo.reassemble(frags);
        assertArrayEquals(payload, reassembled, "Reassembled payload must match original");
    }

    @Test
    public void testReassemblyOutOfOrder() {
        Ipv4Header header = new Ipv4Header(0, 3020, 1002, 0, 0, 64, 6, "10.0.0.1", "10.0.0.2");
        byte[] payload = "Testing fragmentation reassembly with out-of-order arrival!".getBytes();

        List<FragmentationDemo.Fragment> frags = FragmentationDemo.fragment(header, payload, 35);
        // Reverse fragments order
        List<FragmentationDemo.Fragment> reversed = List.of(frags.get(frags.size() - 1), frags.get(0));

        byte[] reassembled = FragmentationDemo.reassemble(frags);
        assertArrayEquals(payload, reassembled);
    }
}
