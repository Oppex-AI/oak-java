package ai.oak.tools.transport;

import ai.oak.tools.protocol.RemoteStep;

/** Called when a step arrives. Implementations must not block the transport thread. */
@FunctionalInterface
public interface StepHandler {
    void onStep(RemoteStep step);
}
