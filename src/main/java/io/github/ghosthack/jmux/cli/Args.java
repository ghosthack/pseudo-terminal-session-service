package io.github.ghosthack.jmux.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The whole option grammar the CLI needs: {@code --flag}, {@code --option VALUE} or
 * {@code --option=VALUE}, anywhere on the line, and positionals in order. Each
 * subcommand declares which names it accepts; anything else is a usage error, so a typo
 * never silently becomes a session name.
 */
final class Args {

    /** A malformed command line; the message is what the user sees. */
    static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    private final List<String> positionals = new ArrayList<>();
    private final Map<String, String> values = new HashMap<>();
    private final Set<String> flags = new HashSet<>();

    static Args parse(List<String> argv, Set<String> valueOptions, Set<String> flagOptions) {
        Args args = new Args();
        for (int i = 0; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (!arg.startsWith("--") || arg.length() == 2) {
                args.positionals.add(arg);
                continue;
            }
            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (eq > 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }
            if (flagOptions.contains(name)) {
                if (inline != null) {
                    throw new UsageException(name + " takes no value");
                }
                args.flags.add(name);
            } else if (valueOptions.contains(name)) {
                String value = inline;
                if (value == null) {
                    if (i + 1 >= argv.size()) {
                        throw new UsageException(name + " needs a value");
                    }
                    value = argv.get(++i);
                }
                args.values.put(name, value);
            } else {
                throw new UsageException("unknown option " + name);
            }
        }
        return args;
    }

    List<String> positionals() {
        return positionals;
    }

    /** The positional at {@code index}, or a usage error naming what was expected. */
    String positional(int index, String what) {
        if (index >= positionals.size()) {
            throw new UsageException(what + " is required");
        }
        return positionals.get(index);
    }

    Optional<String> value(String option) {
        return Optional.ofNullable(values.get(option));
    }

    boolean flag(String option) {
        return flags.contains(option);
    }

    int intValue(String option, int fallback) {
        return value(option).map(v -> {
            try {
                return Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                throw new UsageException(option + " must be an integer, not '" + v + "'");
            }
        }).orElse(fallback);
    }

    long longValue(String option, long fallback) {
        return value(option).map(v -> {
            try {
                return Long.parseLong(v.strip());
            } catch (NumberFormatException e) {
                throw new UsageException(option + " must be an integer, not '" + v + "'");
            }
        }).orElse(fallback);
    }

    /** {@code --size CxR} as {columns, rows}. */
    static int[] size(String value) {
        String[] parts = value.toLowerCase().split("x");
        try {
            if (parts.length == 2) {
                int columns = Integer.parseInt(parts[0].strip());
                int rows = Integer.parseInt(parts[1].strip());
                if (columns > 0 && rows > 0) {
                    return new int[]{columns, rows};
                }
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new UsageException("--size must be COLUMNSxROWS, e.g. 120x40, not '" + value + "'");
    }
}
