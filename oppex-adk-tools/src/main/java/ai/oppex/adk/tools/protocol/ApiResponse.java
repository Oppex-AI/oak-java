package ai.oppex.adk.tools.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The envelope every Oppex REST endpoint replies with: {@code {success, statusCode, message, data}}.
 *
 * <p>{@code message} is always populated on a failure and is written to be shown to a person, so
 * log it verbatim rather than inventing text from the HTTP status.
 *
 * <p>Unknown properties are ignored deliberately. Oppex may add fields to this envelope, and an
 * agent running on a customer host must not start failing because the platform grew a field it
 * has never heard of. The same applies to every inbound type in this package.
 *
 * @param <T> the payload type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiResponse<T> {

    private boolean success;
    private int statusCode;
    private String message;
    private T data;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
