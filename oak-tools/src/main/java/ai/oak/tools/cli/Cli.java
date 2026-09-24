package ai.oak.tools.cli;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Tiny fluent builder for a shell command line (AWS CLI, docker, kubectl, ...), assembled from a
 * runbook step's resolved inputs.
 *
 * <p>Optional flags are omitted when their input is absent; a required flag or positional emits a
 * {@code <placeholder>} so a print/preview command still reads as a template when the plan left the
 * value unresolved. A list-valued input (e.g. several instance ids) is space-joined. Framework-free
 * and shared by every tool package.
 */
public final class Cli {

    private final StringBuilder command;

    private Cli(final String head) {
        this.command = new StringBuilder(head);
    }

    /** {@code aws <service> <action>}. */
    public static Cli aws(final String service, final String action) {
        return new Cli("aws " + service + " " + action);
    }

    /** {@code docker <action>}. */
    public static Cli docker(final String action) {
        return new Cli("docker " + action);
    }

    /** Optional scalar flag — omitted entirely when the input is absent/blank. */
    public Cli opt(final String flag, final Object value) {
        if (present(value)) {
            command.append(' ').append(flag).append(' ').append(value);
        }
        return this;
    }

    /** Optional list flag — space-joins the value, omitted when absent/blank. */
    public Cli optList(final String flag, final Object value) {
        final String joined = joinTokens(value);
        if (!joined.isEmpty()) {
            command.append(' ').append(flag).append(' ').append(joined);
        }
        return this;
    }

    /** Required list flag — space-joins the value, or emits {@code placeholder} when absent. */
    public Cli required(final String flag, final Object value, final String placeholder) {
        final String joined = joinTokens(value);
        command.append(' ').append(flag).append(' ').append(joined.isEmpty() ? placeholder : joined);
        return this;
    }

    /** Bare boolean flag (e.g. {@code --all}) — appended only when the input is truthy. */
    public Cli flag(final String name, final Object enabled) {
        if (truthy(enabled)) {
            command.append(' ').append(name);
        }
        return this;
    }

    /** Required positional argument (e.g. a docker container) — {@code placeholder} when absent. */
    public Cli positional(final Object value, final String placeholder) {
        command.append(' ').append(present(value) ? value : placeholder);
        return this;
    }

    public String build() {
        return command.toString();
    }

    private static boolean present(final Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static boolean truthy(final Object value) {
        return value != null && ("true".equalsIgnoreCase(value.toString()) || Boolean.TRUE.equals(value));
    }

    private static String joinTokens(final Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).filter(token -> !token.isBlank()).collect(Collectors.joining(" "));
        }
        return present(value) ? value.toString() : "";
    }
}
