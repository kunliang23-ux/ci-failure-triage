package com.kunliang.citriage.storage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Explicit execution metadata. Absent fields are unknown. */
public record RunMetadata(Map<Field, String> values) {
    public RunMetadata {
        var copy = new EnumMap<Field, String>(Field.class);
        copy.putAll(values);
        if (copy.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Supplied metadata must be nonblank.");
        }
        values = Collections.unmodifiableMap(copy);
    }

    public static RunMetadata supplied(String commit, String environment) {
        var values = new EnumMap<Field, String>(Field.class);
        if (commit != null) {
            values.put(Field.COMMIT, commit);
        }
        if (environment != null) {
            values.put(Field.ENVIRONMENT, environment);
        }
        return new RunMetadata(values);
    }

    public Optional<String> value(Field field) {
        return Optional.ofNullable(values.get(field));
    }

    public enum Field {
        COMMIT("Commit"),
        ENVIRONMENT("Environment"),
        REPOSITORY("Repository"),
        WORKFLOW("Workflow"),
        JOB("Job"),
        ATTEMPT("Attempt"),
        EXECUTED_AT("Execution time"),
        TEST_COMMAND("Test command"),
        TEST_CONFIGURATION("Test configuration"),
        OS("OS"),
        ARCHITECTURE("Architecture"),
        TOOLCHAIN("Toolchain"),
        DEPENDENCY_FINGERPRINT("Dependency fingerprint");

        private final String label;

        Field(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
