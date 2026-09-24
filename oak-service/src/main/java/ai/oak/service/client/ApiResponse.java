package ai.oak.service.client;

/**
 * The envelope every {@code /v1/tools} response arrives in. Restated here from the platform's own
 * {@code APIResponse} so this SDK need not depend on its internals — the shape is the contract, not
 * the class. {@code data} is null on an error, and on a poll that found nothing to do.
 */
public record ApiResponse<T>(boolean success, int code, String message, T data) {
}
