package ai.oppex.adk.tools.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent on startup: who this agent is and what it can run.
 *
 * <p>Registration replaces the previous declaration wholesale, so a restart with a tool removed
 * genuinely removes it. {@code name} should be stable across restarts — Oppex keys the registration
 * on (workspace, name), so a name that changes every boot accumulates dead registrations, and
 * "their agent is down" stops being distinguishable from "they never configured one".
 *
 * <p>Client and workspace are NOT in this payload. Oppex resolves both from the API key, so an
 * agent cannot register into a workspace it does not hold a key for.
 */
public class RegistrationRequest {

    private String name;
    private String version;
    private List<CapabilityDeclaration> capabilities = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<CapabilityDeclaration> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<CapabilityDeclaration> capabilities) {
        this.capabilities = capabilities;
    }
}
