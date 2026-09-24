package ai.oak.tools.config;

/**
 * One platform this agent takes work from.
 *
 * <p>Configuration is a <em>list</em> of these even though Oppex is the only one today. Nothing in
 * the protocol is Oppex-specific, so another incident platform could drive the same agent — and
 * the customer still exposes nothing, because the agent dials out to each one. Shaping it as a list
 * now costs nothing and is awkward to retrofit once someone has a config file in production.
 */
public class VendorConfig {

    private String name = "oppex";
    private String baseUrl;
    private String apiKey;

    public VendorConfig() {
    }

    public VendorConfig(String name, String baseUrl, String apiKey) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public String getName() {
        return name;
    }

    public VendorConfig setName(String name) {
        this.name = name;
        return this;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public VendorConfig setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    public String getApiKey() {
        return apiKey;
    }

    public VendorConfig setApiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    /** Never let the key reach a log or a crash report. */
    @Override
    public String toString() {
        return "VendorConfig{name='" + name + "', baseUrl='" + baseUrl + "', apiKey=***}";
    }
}
