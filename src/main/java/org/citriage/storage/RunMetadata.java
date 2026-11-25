package org.citriage.storage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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

    public Optional<String> knownValue(Field field) {
        return value(field).filter(value -> !value.strip().equalsIgnoreCase("unknown"));
    }

    public Optional<Instant> instant(Field field) {
        try {
            return knownValue(field).map(RunMetadata::parseTimestamp);
        } catch (DateTimeParseException exception) {
            // Older databases can contain raw timestamp values supplied by Java callers.
            return Optional.empty();
        }
    }

    public RunMetadata merge(RunMetadata other) {
        var merged = new EnumMap<Field, String>(Field.class);
        merged.putAll(values);
        for (var entry : other.values().entrySet()) {
            String previous = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                throw new IllegalArgumentException("Conflicting " + entry.getKey().name() + " metadata.");
            }
        }
        return new RunMetadata(merged);
    }

    static Instant parseTimestamp(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }

    void validateChronology() {
        Optional<Instant> start = instant(Field.EXECUTED_AT);
        Optional<Instant> end = instant(Field.COMPLETED_AT);
        if (start.isPresent() && end.isPresent() && end.get().isBefore(start.get())) {
            throw new IllegalArgumentException("COMPLETED_AT precedes EXECUTED_AT.");
        }
    }

    public enum Conclusion {
        SUCCESS, FAILURE, CANCELLED, TIMED_OUT, SKIPPED, NEUTRAL
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
        DEPENDENCY_FINGERPRINT("Dependency fingerprint"),
        CONCLUSION("Run conclusion"),
        COMPLETED_AT("Completion time");

        private final String label;

        Field(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
