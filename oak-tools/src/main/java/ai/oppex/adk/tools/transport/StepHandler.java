package ai.oppex.adk.tools.transport;

import ai.oppex.adk.tools.protocol.RemoteStep;

/** Called when a step arrives. Implementations must not block the transport thread. */
@FunctionalInterface
public interface StepHandler {
    void onStep(RemoteStep step);
}
