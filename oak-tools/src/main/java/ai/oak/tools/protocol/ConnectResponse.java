package ai.oak.tools.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Reply to {@code POST /v1/tools/connect}: where to open the WebSocket, and a short-lived
 * single-use ticket to open it with.
 *
 * <p>The ticket exists so the long-lived API key never travels in a WebSocket URL. URLs end up in
 * proxy logs, browser histories and crash reports in a way that request headers do not; a ticket
 * that expires in seconds is worth far less to anyone who finds it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectResponse {

    private String websocketUrl;
    private String ticket;
    private Integer heartbeatSeconds;

    public String getWebsocketUrl() {
        return websocketUrl;
    }

    public void setWebsocketUrl(String websocketUrl) {
        this.websocketUrl = websocketUrl;
    }

    public String getTicket() {
        return ticket;
    }

    public void setTicket(String ticket) {
        this.ticket = ticket;
    }

    public Integer getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(Integer heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }
}
