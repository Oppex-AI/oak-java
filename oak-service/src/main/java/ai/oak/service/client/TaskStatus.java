package ai.oak.service.client;

/**
 * The outcome status this service reports for a step. The platform's own task lifecycle has more
 * states, but a tool service only ever produces these two — it ran the step or it did not.
 */
public enum TaskStatus {
    SUCCESS,
    FAILED
}
