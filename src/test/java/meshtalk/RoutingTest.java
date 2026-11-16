package meshtalk;

import meshtalk.nettools.RoutingSimulator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RoutingTest {

    @Test
    public void testDijkstraKuroseTopology() {
        RoutingSimulator.Topology top = RoutingSimulator.Topology.createKuroseTopology();
        RoutingSimulator.DijkstraResult res = RoutingSimulator.dijkstra(top, "u");

        assertNotNull(res);
        assertEquals(6, res.routingTable.size());

        // Textbook costs from source u in Kurose-Ross 6-node graph:
        // u->u=0, u->x=1, u->v=2, u->y=2 (via x), u->w=3 (via x->y or v), u->z=4 (via x->y->z)
        assertEquals(0, res.routingTable.get("u").cost);
        assertEquals(1, res.routingTable.get("x").cost);
        assertEquals(2, res.routingTable.get("v").cost);
        assertEquals(2, res.routingTable.get("y").cost);
        assertEquals(3, res.routingTable.get("w").cost);
        assertEquals(4, res.routingTable.get("z").cost);
    }

    @Test
    public void testDistanceVectorConvergence() {
        RoutingSimulator.Topology top = RoutingSimulator.Topology.createKuroseTopology();
        RoutingSimulator.DvResult res = RoutingSimulator.distanceVector(top);

        assertNotNull(res);
        assertTrue(res.rounds.size() >= 2, "Distance Vector must take multiple rounds to converge");

        // Verify routing table costs match Dijkstra
        assertEquals(4, res.routingTables.get("u").get("z").cost);
        assertEquals(2, res.routingTables.get("u").get("y").cost);
    }

    @Test
    public void testCountToInfinitySimulation() {
        String withoutFix = RoutingSimulator.demonstrateCountToInfinity(false);
        assertTrue(withoutFix.contains("COUNT-TO-INFINITY"));

        String withFix = RoutingSimulator.demonstrateCountToInfinity(true);
        assertTrue(withFix.contains("POISON REVERSE"));
        assertTrue(withFix.contains("Immediate convergence"));
    }
}
