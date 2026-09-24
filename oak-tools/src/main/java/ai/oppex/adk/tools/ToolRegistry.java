package ai.oppex.adk.tools;

import ai.oppex.adk.tools.protocol.CapabilityDeclaration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What this agent can do. The only place a capability name is resolved to code.
 *
 * <p>Registration order is last-one-wins for a given name, which is what lets you override a
 * built-in with your own implementation simply by registering yours after it.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Capability> byName = new ConcurrentHashMap<>();

    /** Registers a capability, replacing any previous one with the same name. */
    public ToolRegistry register(Capability capability) {
        if (capability == null) {
            throw new IllegalArgumentException("capability must not be null");
        }
        final String name = capability.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    capability.getClass().getName() + " returned a blank name()");
        }
        final Capability previous = byName.put(name, capability);
        if (previous != null) {
            log.info("Capability {} re-registered: {} replaces {}", name,
                    capability.getClass().getName(), previous.getClass().getName());
        } else {
            log.info("Capability registered: {} ({}, {})", name,
                    capability.getClass().getSimpleName(), capability.permission());
        }
        return this;
    }

    /**
     * Registers a capability that may not be loadable, and says so rather than failing to start.
     *
     * <p>This exists because the AWS SDK is an <em>optional</em> dependency. A customer whose tools
     * are all their own should not have to ship it, and the failure when it is absent is a
     * {@link NoClassDefFoundError} at class-load time — an Error, not an Exception, so a plain
     * try/catch on Exception would not hold it. Without this the agent would refuse to boot with a
     * stack trace about a class the operator never asked for.
     *
     * @param supplier deferred construction, so the class is not touched until called
     */
    public ToolRegistry registerIfAvailable(String name, java.util.function.Supplier<Capability> supplier) {
        try {
            register(supplier.get());
        } catch (NoClassDefFoundError | RuntimeException e) {
            log.info("Capability {} not available, skipping ({}: {}). "
                            + "Add the matching optional dependency if you want it.",
                    name, e.getClass().getSimpleName(), e.getMessage());
        }
        return this;
    }

    public Optional<Capability> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Everything registered, in a stable order, for the registration call. */
    public List<CapabilityDeclaration> declarations() {
        final Map<String, Capability> snapshot = new LinkedHashMap<>(byName);
        final List<CapabilityDeclaration> out = new ArrayList<>(snapshot.size());
        snapshot.forEach((name, cap) -> out.add(new CapabilityDeclaration(name, cap.permission())));
        return out;
    }

    public int size() {
        return byName.size();
    }
}
