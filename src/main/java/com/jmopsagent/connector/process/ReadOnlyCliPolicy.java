package com.jmopsagent.connector.process;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Defense in depth for CLI adapters; semantic connector methods are the primary boundary. */
public final class ReadOnlyCliPolicy {
    private static final Set<String> KUBECTL_MUTATING = Set.of(
            "apply", "create", "delete", "edit", "patch", "replace", "scale", "set", "exec", "cp",
            "port-forward", "proxy", "attach", "run", "expose", "autoscale", "drain", "cordon", "uncordon",
            "taint", "label", "annotate", "certificate", "top");
    private static final Set<String> CF_ALLOWED = Set.of("app", "apps", "logs", "env", "routes");

    private ReadOnlyCliPolicy() {
    }

    public static void validateKubectl(List<String> arguments) {
        int commandIndex = afterPairedGlobalOptions(arguments, Set.of("--context", "--namespace"));
        if (commandIndex >= arguments.size()) throw rejected("kubectl command is missing");
        List<String> normalized = arguments.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
        if (normalized.stream().anyMatch(KUBECTL_MUTATING::contains)) {
            throw rejected("mutating kubectl verb is prohibited");
        }
        String command = normalized.get(commandIndex);
        if (command.equals("get") || command.equals("logs")) return;
        if (command.equals("rollout") && commandIndex + 1 < normalized.size()
                && normalized.get(commandIndex + 1).equals("status")) return;
        throw rejected("kubectl operation is not on the read-only allowlist");
    }

    public static void validateCf(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            throw rejected("CF command is missing");
        }
        String rawCommand = arguments.getFirst();
        if (rawCommand == null) throw rejected("CF command is missing");
        String command = rawCommand.toLowerCase(Locale.ROOT);
        if (command.equals("target")) {
            if (arguments.size() == 1) return;
            throw rejected("CF target mutation is prohibited");
        }
        if (!CF_ALLOWED.contains(command)) {
            throw rejected("CF operation is not on the read-only allowlist");
        }
    }

    private static int afterPairedGlobalOptions(List<String> arguments, Set<String> options) {
        int index = 0;
        while (index < arguments.size() && options.contains(arguments.get(index))) {
            if (index + 1 >= arguments.size()) throw rejected("CLI global option is missing its value");
            index += 2;
        }
        return index;
    }

    private static IllegalArgumentException rejected(String reason) {
        return new IllegalArgumentException("Read-only process policy rejected the request: " + reason);
    }
}
