package meshtalk;

import meshtalk.server.ConcurrencyDemo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrencyTest {

    @Test
    public void testRaceConditionDemo() throws InterruptedException {
        String output = ConcurrencyDemo.demonstrateRaceCondition(8, 10000);
        assertNotNull(output);
        assertTrue(output.contains("RACE CONDITION DEMONSTRATION"));
        assertTrue(output.contains("FIXED - ATOMIC / SYNC"));
        assertTrue(output.contains("Result = 80000 (Exact match!)"));
    }

    @Test
    public void testDeadlockPrevention() {
        String fixed = ConcurrencyDemo.demonstrateDeadlock(true);
        assertNotNull(fixed);
        assertTrue(fixed.contains("DEADLOCK PREVENTION"));
        assertTrue(fixed.contains("SUCCESS! No deadlock"));

        String deadlocked = ConcurrencyDemo.demonstrateDeadlock(false);
        assertNotNull(deadlocked);
        assertTrue(deadlocked.contains("DEADLOCK DETECTED"));
    }
}
