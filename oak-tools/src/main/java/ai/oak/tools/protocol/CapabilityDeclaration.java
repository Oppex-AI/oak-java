package ai.oak.tools.protocol;

import ai.oak.tools.ToolPermission;

/** One capability this agent is telling Oppex it can execute. */
public class CapabilityDeclaration {

    private String capability;
    private ToolPermission permission;

    public CapabilityDeclaration() {
    }

    public CapabilityDeclaration(String capability, ToolPermission permission) {
        this.capability = capability;
        this.permission = permission;
    }

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public ToolPermission getPermission() {
        return permission;
    }

    public void setPermission(ToolPermission permission) {
        this.permission = permission;
    }
}
