package meshtalk;

import meshtalk.nettools.SubnetCalculator;
import meshtalk.nettools.VlsmPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SubnetCalculatorTest {

    @Test
    public void testClassfulAndCidrSubnet() {
        SubnetCalculator.SubnetInfo info = SubnetCalculator.calculate("192.168.1.130/26");
        assertEquals("192.168.1.128", info.networkAddress);
        assertEquals("192.168.1.191", info.broadcastAddress);
        assertEquals("192.168.1.129", info.firstUsableHost);
        assertEquals("192.168.1.190", info.lastUsableHost);
        assertEquals(62, info.usableHosts);
        assertEquals("255.255.255.192", info.subnetMask);
        assertEquals('C', info.ipClass);
        assertTrue(info.isPrivate);
    }

    @Test
    public void testClassA() {
        SubnetCalculator.SubnetInfo info = SubnetCalculator.calculate("10.50.100.1/8");
        assertEquals("10.0.0.0", info.networkAddress);
        assertEquals("10.255.255.255", info.broadcastAddress);
        assertEquals('A', info.ipClass);
        assertTrue(info.isPrivate);
    }

    @Test
    public void testClassB() {
        SubnetCalculator.SubnetInfo info = SubnetCalculator.calculate("172.16.10.5/16");
        assertEquals("172.16.0.0", info.networkAddress);
        assertEquals("172.16.255.255", info.broadcastAddress);
        assertEquals('B', info.ipClass);
        assertTrue(info.isPrivate);
    }

    @Test
    public void testInvalidIpThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> SubnetCalculator.calculate("999.1.1.1/24"));
        assertThrows(IllegalArgumentException.class, () -> SubnetCalculator.calculate("192.168.1.1/35"));
    }

    @Test
    public void testVlsmPlanner() {
        List<VlsmPlanner.HostRequirement> reqs = List.of(
                new VlsmPlanner.HostRequirement("Department A", 60),
                new VlsmPlanner.HostRequirement("Department B", 25),
                new VlsmPlanner.HostRequirement("Department C", 10)
        );

        List<VlsmPlanner.VlsmAllocation> allocs = new VlsmPlanner().plan("192.168.1.0/24", reqs);
        assertEquals(3, allocs.size());
        assertTrue(allocs.get(0).usableHosts >= 60);
        assertTrue(allocs.get(1).usableHosts >= 25);
        assertTrue(allocs.get(2).usableHosts >= 10);
    }
}
