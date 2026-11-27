package org.citriage.github;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonObject;

import org.citriage.report.ReportSource;
import org.citriage.storage.RunMetadata;
import org.citriage.storage.RunMetadata.Field;

/** The version 1 producer contract, validated separately from GitHub job metadata. */
record BundleManifest(String repository, long repositoryId, long runId, int attempt, String workflow, String job,
                      Optional<String> testedCommit, Optional<String> triggeringHead, RunMetadata execution,
                      List<Entry> files) {
    private static final Map<String, Field> EXECUTION_FIELDS = Map.of(
            "environment", Field.ENVIRONMENT, "test_command", Field.TEST_COMMAND,
            "test_configuration", Field.TEST_CONFIGURATION, "os", Field.OS, "architecture", Field.ARCHITECTURE,
            "toolchain", Field.TOOLCHAIN, "dependency_fingerprint", Field.DEPENDENCY_FINGERPRINT);

    static BundleManifest read(ReportSource source) throws IOException {
        JsonObject root;
        try {
            root = JsonFields.read(source.bytes());
        } catch (IOException exception) {
            throw new IOException("Invalid bundle manifest. Expected strict UTF-8 JSON with unique field names.",
                    exception);
        }
        try {
            if (JsonFields.number(root, "version", 1) != 1) {
                throw new IOException("Unsupported triage bundle manifest version.");
            }
            JsonFields.onlyKeys(root, "version", "repository", "repository_id", "run_id", "attempt", "workflow", "job",
                    "tested_commit", "triggering_head_commit", "execution", "files");
            return new BundleManifest(JsonFields.text(root, "repository"), JsonFields.number(root, "repository_id", 1),
                    JsonFields.number(root, "run_id", 1), JsonFields.attempt(root, "attempt"),
                    JsonFields.text(root, "workflow"), JsonFields.text(root, "job"), commit(root, "tested_commit"),
                    commit(root, "triggering_head_commit"), execution(root), files(root));
        } catch (IOException exception) {
            throw new IOException("Invalid bundle manifest. " + exception.getMessage(), exception);
        }
    }

    private static Optional<String> commit(JsonObject root, String key) throws IOException {
        Optional<String> value = JsonFields.optionalText(root, key);
        if (value.isPresent() && !value.get().matches("(?:[a-f0-9]{40}|[a-f0-9]{64})")) {
            throw new IOException("Manifest '" + key + "' must be a full lowercase commit SHA.");
        }
        return value;
    }

    private static RunMetadata execution(JsonObject root) throws IOException {
        var fields = new EnumMap<Field, String>(Field.class);
        if (!JsonFields.absent(root.get("execution"))) {
            JsonObject execution = JsonFields.object(root.get("execution"));
            JsonFields.onlyKeys(execution, EXECUTION_FIELDS.keySet().toArray(String[]::new));
            for (var field : EXECUTION_FIELDS.entrySet()) {
                JsonFields.optionalText(execution, field.getKey())
                        .filter(value -> !value.strip().equalsIgnoreCase("unknown"))
                        .ifPresent(value -> fields.put(field.getValue(), value));
            }
        }
        return new RunMetadata(fields);
    }

    private static List<Entry> files(JsonObject root) throws IOException {
        var files = new ArrayList<Entry>();
        var paths = new HashSet<String>();
        for (var element : JsonFields.array(root, "files")) {
            JsonObject file = JsonFields.object(element);
            JsonFields.onlyKeys(file, "path", "kind", "size", "sha256");
            String name = JsonFields.text(file, "path");
            String kind = JsonFields.text(file, "kind");
            if (!((BundleArchive.BUILD_LOG.equals(name) && "log".equals(kind))
                    || (BundleArchive.isJUnitPath(name) && "junit".equals(kind))) || !paths.add(name)) {
                throw new IOException("The file inventory has an unsupported or duplicate evidence entry.");
            }
            String sha256 = JsonFields.text(file, "sha256");
            long size = JsonFields.number(file, "size", 0);
            if (!sha256.matches("[a-f0-9]{64}") || size > BundleArchive.MAX_FILE_BYTES) {
                throw new IOException("The file inventory has an invalid SHA-256 or entry size.");
            }
            files.add(new Entry(name, size, sha256));
        }
        return List.copyOf(files);
    }

    record Entry(String name, long size, String sha256) {
    }
}
