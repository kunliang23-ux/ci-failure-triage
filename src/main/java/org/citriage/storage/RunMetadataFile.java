package org.citriage.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;

import org.citriage.storage.RunMetadata.Field;

/** Reads one literal UTF-8 KEY=value entry per line. */
public final class RunMetadataFile {
    public RunMetadata read(Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
            throw new IOException("Expected a regular metadata file.");
        }
        var values = new EnumMap<Field, String>(Field.class);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            for (int number = 1; (line = reader.readLine()) != null; number++) {
                if (number == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                try {
                    int separator = line.indexOf('=');
                    if (separator < 0) {
                        throw new IllegalArgumentException("Expected KEY=value.");
                    }
                    Field field = field(line.substring(0, separator).strip());
                    String value = line.substring(separator + 1).strip();
                    if (values.containsKey(field)) {
                        throw new IllegalArgumentException("Duplicate key " + field.name() + ".");
                    }
                    validateValue(field, value);
                    values.put(field, value);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Line " + number + ": " + exception.getMessage(), exception);
                }
            }
        }
        RunMetadata metadata = new RunMetadata(values);
        try {
            metadata.validateChronology();
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        return metadata;
    }

    private static Field field(String name) {
        try {
            return Field.valueOf(name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown metadata key '" + name + "'.", exception);
        }
    }

    private static void validateValue(Field field, String value) {
        if (value.isBlank() || value.equalsIgnoreCase("unknown")
                || value.chars().anyMatch(character -> character != '\t' && Character.isISOControl(character))) {
            throw new IllegalArgumentException(field.name() + " requires a nonblank known value. Omit unknown fields.");
        }
        switch (field) {
            case EXECUTED_AT, COMPLETED_AT -> {
                try {
                    RunMetadata.parseTimestamp(value);
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException(field.name() + " requires an ISO-8601 timestamp with an offset.");
                }
            }
            case CONCLUSION -> {
                try {
                    RunMetadata.Conclusion.valueOf(value);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unsupported CONCLUSION '" + value + "'.");
                }
            }
            case ATTEMPT -> {
                if (!value.matches("[0-9]+") || !validAttempt(value)) {
                    throw new IllegalArgumentException("ATTEMPT requires a positive 32-bit integer.");
                }
            }
            default -> { }
        }
    }

    private static boolean validAttempt(String value) {
        try {
            return Integer.parseInt(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
