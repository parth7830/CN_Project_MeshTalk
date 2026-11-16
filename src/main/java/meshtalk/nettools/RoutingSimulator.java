package meshtalk.nettools;

import meshtalk.common.MeshLogger;

import java.util.*;

/**
 * Simulates network layer routing algorithms:
 * <ul>
 *   <li><b>Link State (Dijkstra)</b>: Global knowledge, step-by-step shortest path tree.</li>
 *   <li><b>Distance Vector (Bellman-Ford)</b>: Distributed exchange of vectors until convergence.</li>
 *   <li><b>Count-to-Infinity</b>: Failure of a link causing routing loops, with Split Horizon and Poison Reverse fixes.</li>
 * </ul>
 */
public class RoutingSimulator {

    private static final MeshLogger log = new MeshLogger("RoutingSimulator");
    public static final int INFINITY = 999;

    /**
     * Graph topology representing routers and links with costs.
     */
    public static class Topology {
        private final Set<String> nodes = new TreeSet<>();
        private final Map<String, Map<String, Integer>> adj = new TreeMap<>();

        public void addNode(String node) {
            nodes.add(node);
            adj.putIfAbsent(node, new TreeMap<>());
        }

        public void addEdge(String u, String v, int cost) {
            addNode(u);
            addNode(v);
            adj.get(u).put(v, cost);
            adj.get(v).put(u, cost);
        }

        public void removeEdge(String u, String v) {
            if (adj.containsKey(u)) adj.get(u).remove(v);
            if (adj.containsKey(v)) adj.get(v).remove(u);
        }

        public Set<String> getNodes() { return Collections.unmodifiableSet(nodes); }
        public Map<String, Integer> getNeighbors(String u) {
            return adj.getOrDefault(u, Collections.emptyMap());
        }

        public int getCost(String u, String v) {
            if (u.equals(v)) return 0;
            return adj.getOrDefault(u, Collections.emptyMap()).getOrDefault(v, INFINITY);
        }

        /**
         * Parses a topology string like "u-v:2, v-w:3, u-w:5".
         */
        public static Topology fromString(String spec) {
            Topology t = new Topology();
            for (String part : spec.split("[,;\\n]")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String[] edgeAndCost = part.split(":");
                String[] uv = edgeAndCost[0].trim().split("[-<>]");
                int cost = Integer.parseInt(edgeAndCost[1].trim());
                t.addEdge(uv[0].trim(), uv[1].trim(), cost);
            }
            return t;
        }

        /** Creates classic Kurose-Ross 6-node textbook topology (u, v, w, x, y, z). */
        public static Topology createKuroseTopology() {
            Topology t = new Topology();
            t.addEdge("u", "v", 2);
            t.addEdge("u", "x", 1);
            t.addEdge("u", "w", 5);
            t.addEdge("v", "w", 3);
            t.addEdge("v", "x", 2);
            t.addEdge("w", "x", 3);
            t.addEdge("w", "y", 1);
            t.addEdge("w", "z", 5);
            t.addEdge("x", "y", 1);
            t.addEdge("y", "z", 2);
            return t;
        }
    }

    /**
     * Routing table entry: destination -> nextHop, cost, path.
     */
    public static class RouteEntry {
        public final String destination;
        public final String nextHop;
        public final int cost;
        public final List<String> path;

        public RouteEntry(String destination, String nextHop, int cost, List<String> path) {
            this.destination = destination;
            this.nextHop = nextHop;
            this.cost = cost;
            this.path = path;
        }

        @Override
        public String toString() {
            return String.format("%-4s -> NextHop: %-4s | Cost: %-3d | Path: %s",
                    destination, nextHop, cost, String.join(" -> ", path));
        }
    }

    /**
     * Step snapshot during Dijkstra's algorithm iteration.
     */
    public static class DijkstraStep {
        public final int stepNumber;
        public final String addedNode;
        public final Set<String> visitedSet;
        public final Map<String, Integer> tentativeDistances;
        public final Map<String, String> predecessors;

        public DijkstraStep(int stepNumber, String addedNode, Set<String> visitedSet,
                            Map<String, Integer> dist, Map<String, String> pred) {
            this.stepNumber = stepNumber;
            this.addedNode = addedNode;
            this.visitedSet = new TreeSet<>(visitedSet);
            this.tentativeDistances = new TreeMap<>(dist);
            this.predecessors = new TreeMap<>(pred);
        }
    }

    public static class DijkstraResult {
        public final String source;
        public final List<DijkstraStep> steps = new ArrayList<>();
        public final Map<String, RouteEntry> routingTable = new TreeMap<>();

        public DijkstraResult(String source) {
            this.source = source;
        }
    }

    /**
     * Runs Dijkstra's Link-State Routing Algorithm.
     */
    public static DijkstraResult dijkstra(Topology topology, String source) {
        DijkstraResult res = new DijkstraResult(source);
        Set<String> visited = new TreeSet<>();
        Map<String, Integer> dist = new TreeMap<>();
        Map<String, String> pred = new TreeMap<>();

        for (String node : topology.getNodes()) {
            dist.put(node, INFINITY);
            pred.put(node, null);
        }
        dist.put(source, 0);

        int stepNum = 0;
        while (visited.size() < topology.getNodes().size()) {
            String u = null;
            int minDist = INFINITY + 1;
            for (String node : topology.getNodes()) {
                if (!visited.contains(node) && dist.get(node) < minDist) {
                    minDist = dist.get(node);
                    u = node;
                }
            }

            if (u == null || minDist >= INFINITY) break; // unreachable nodes

            visited.add(u);
            res.steps.add(new DijkstraStep(stepNum++, u, visited, dist, pred));

            for (Map.Entry<String, Integer> edge : topology.getNeighbors(u).entrySet()) {
                String v = edge.getKey();
                int weight = edge.getValue();
                if (!visited.contains(v)) {
                    int alt = dist.get(u) + weight;
                    if (alt < dist.get(v)) {
                        dist.put(v, alt);
                        pred.put(v, u);
                    }
                }
            }
        }

        // Build routing table
        for (String dest : topology.getNodes()) {
            if (dest.equals(source)) {
                res.routingTable.put(source, new RouteEntry(source, "LOCAL", 0, List.of(source)));
                continue;
            }
            if (dist.get(dest) >= INFINITY) {
                res.routingTable.put(dest, new RouteEntry(dest, "NONE", INFINITY, Collections.emptyList()));
                continue;
            }

            // Reconstruct path
            LinkedList<String> path = new LinkedList<>();
            String curr = dest;
            while (curr != null) {
                path.addFirst(curr);
                if (curr.equals(source)) break;
                curr = pred.get(curr);
            }

            String nextHop = (path.size() >= 2) ? path.get(1) : dest;
            res.routingTable.put(dest, new RouteEntry(dest, nextHop, dist.get(dest), path));
        }

        return res;
    }

    /**
     * Distance Vector step representation.
     */
    public static class DvIteration {
        public final int iteration;
        // Node -> (Dest -> Cost)
        public final Map<String, Map<String, Integer>> distanceVectors = new TreeMap<>();

        public DvIteration(int iteration) {
            this.iteration = iteration;
        }
    }

    public static class DvResult {
        public final List<DvIteration> rounds = new ArrayList<>();
        public final Map<String, Map<String, RouteEntry>> routingTables = new TreeMap<>();
    }

    /**
     * Runs Distance Vector (Bellman-Ford) until convergence.
     */
    public static DvResult distanceVector(Topology topology) {
        DvResult result = new DvResult();
        Set<String> nodes = topology.getNodes();

        // Node -> Dest -> Cost
        Map<String, Map<String, Integer>> table = new TreeMap<>();
        // Node -> Dest -> NextHop
        Map<String, Map<String, String>> nextHop = new TreeMap<>();

        for (String u : nodes) {
            table.put(u, new TreeMap<>());
            nextHop.put(u, new TreeMap<>());
            for (String v : nodes) {
                if (u.equals(v)) {
                    table.get(u).put(v, 0);
                    nextHop.get(u).put(v, "LOCAL");
                } else {
                    int c = topology.getCost(u, v);
                    table.get(u).put(v, c);
                    nextHop.get(u).put(v, (c < INFINITY) ? v : "NONE");
                }
            }
        }

        boolean changed = true;
        int round = 0;

        while (changed && round < 50) {
            DvIteration iter = new DvIteration(round++);
            for (String u : nodes) {
                iter.distanceVectors.put(u, new TreeMap<>(table.get(u)));
            }
            result.rounds.add(iter);

            changed = false;
            // Bellman-Ford equation: D_x(y) = min_v { c(x,v) + D_v(y) }
            Map<String, Map<String, Integer>> newTable = new TreeMap<>();
            for (String u : nodes) newTable.put(u, new TreeMap<>(table.get(u)));

            for (String x : nodes) {
                for (String y : nodes) {
                    if (x.equals(y)) continue;
                    int bestCost = table.get(x).get(y);
                    String bestNext = nextHop.get(x).get(y);

                    for (Map.Entry<String, Integer> neighbor : topology.getNeighbors(x).entrySet()) {
                        String v = neighbor.getKey();
                        int c_xv = neighbor.getValue();
                        int dv_y = table.get(v).get(y);
                        if (c_xv + dv_y < bestCost) {
                            bestCost = c_xv + dv_y;
                            bestNext = v;
                            changed = true;
                        }
                    }
                    newTable.get(x).put(y, bestCost);
                    nextHop.get(x).put(y, bestNext);
                }
            }
            table = newTable;
        }

        // Finalize routing tables
        for (String u : nodes) {
            result.routingTables.put(u, new TreeMap<>());
            for (String v : nodes) {
                result.routingTables.get(u).put(v,
                        new RouteEntry(v, nextHop.get(u).get(v), table.get(u).get(v), List.of(u, nextHop.get(u).get(v), v)));
            }
        }

        return result;
    }

    /**
     * Demonstrates Count-to-Infinity problem and Poison Reverse fix on 3-node topology (A - B - C).
     */
    public static String demonstrateCountToInfinity(boolean withPoisonReverse) {
        StringBuilder sb = new StringBuilder();
        sb.append(withPoisonReverse ? "=== SIMULATION WITH POISON REVERSE ===\n" : "=== SIMULATION: COUNT-TO-INFINITY (WITHOUT FIX) ===\n");
        sb.append("Initial Topology: A <---1---> B <---1---> C\n");

        int c_ab = 1;
        int c_bc = 1;

        int d_ac = 2; // A through B to C
        int d_bc = 1; // B directly to C

        sb.append(String.format("Initial state: D_A(C)=%d (via B), D_B(C)=%d (direct)\n", d_ac, d_bc));
        sb.append("EVENT: Link B-C severed! c(B,C) = INFINITY\n\n");

        c_bc = INFINITY;

        for (int step = 1; step <= 8; step++) {
            sb.append(String.format("Round %d:\n", step));
            if (withPoisonReverse) {
                // With poison reverse: Since A routes to C via B, A advertises D_A(C) = INFINITY to B!
                sb.append("  [Poison Reverse] A routes to C through B -> A tells B that D_A(C) = INFINITY.\n");
                // B calculates D_B(C) = min(c(B,C) + 0, c(B,A) + D_A(C)) = min(INF, 1 + INF) = INF
                d_bc = INFINITY;
                sb.append(String.format("  B computes: D_B(C) = min(c(B,C), c(B,A) + D_A(C)) = min(INF, 1 + INF) = %d\n", d_bc));
                // A then receives update from B that D_B(C) = INF
                d_ac = INFINITY;
                sb.append(String.format("  A computes: D_A(C) = c(A,B) + D_B(C) = 1 + INF = %d\n", d_ac));
                sb.append("  => Immediate convergence! Loop prevented.\n");
                break;
            } else {
                // Without poison reverse:
                // B thinks A has a path to C with cost d_ac!
                d_bc = c_ab + d_ac;
                sb.append(String.format("  B recalculates: D_B(C) = c(B,A) + D_A(C) = 1 + %d = %d (via A!)\n", d_ac, d_bc));
                d_ac = c_ab + d_bc;
                sb.append(String.format("  A recalculates: D_A(C) = c(A,B) + D_B(C) = 1 + %d = %d (via B!)\n", d_bc, d_ac));
                sb.append(String.format("  -> Routing loop: A->B->A->B... Count growing towards infinity!\n"));
            }
        }
        return sb.toString();
    }
}
