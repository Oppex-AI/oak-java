package ai.oak.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static Capability capability(String name, ToolPermission permission) {
        return new Capability() {
            public String name() {
                return name;
            }

            public ToolPermission permission() {
                return permission;
            }

            public Map<String, Object> execute(Map<String, Object> input) {
                return Map.of("ok", true);
            }
        };
    }

    @Test
    @DisplayName("a later registration of the same name wins, so a customer can override a built-in")
    void lastOneWins() {
        final Capability mine = capability("FIND_ASG", ToolPermission.WRITE);
        final ToolRegistry registry = new ToolRegistry()
                .register(capability("FIND_ASG", ToolPermission.READ))
                .register(mine);

        assertEquals(1, registry.size());
        assertEquals(mine, registry.find("FIND_ASG").orElseThrow());
        assertEquals(ToolPermission.WRITE, registry.declarations().get(0).getPermission());
    }

    @Test
    @DisplayName("an unknown name resolves to empty rather than throwing")
    void unknownIsEmpty() {
        assertTrue(new ToolRegistry().find("NOPE").isEmpty());
    }

    @Test
    @DisplayName("a blank name is rejected at registration, not at dispatch")
    void blankNameRejected() {
        final ToolRegistry registry = new ToolRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(capability("  ", ToolPermission.READ)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
    }

    @Test
    @DisplayName("registerIfAvailable swallows a missing optional dependency instead of failing to boot")
    void missingOptionalDependencyIsSkipped() {
        final ToolRegistry registry = new ToolRegistry();
        // NoClassDefFoundError is an Error, not an Exception — a plain catch(Exception) would not
        // hold it, which is the whole reason registerIfAvailable exists.
        registry.registerIfAvailable("ABSENT", () -> {
            throw new NoClassDefFoundError("software/amazon/awssdk/services/autoscaling/AutoScalingClient");
        });
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("declarations carry every registered capability with its permission")
    void declarationsAreComplete() {
        final ToolRegistry registry = new ToolRegistry()
                .register(capability("A", ToolPermission.READ))
                .register(capability("B", ToolPermission.DESTRUCTIVE));

        assertEquals(2, registry.declarations().size());
        assertTrue(registry.declarations().stream()
                .anyMatch(d -> "B".equals(d.getCapability()) && d.getPermission() == ToolPermission.DESTRUCTIVE));
    }
}
