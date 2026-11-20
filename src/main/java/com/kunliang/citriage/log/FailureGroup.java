package com.kunliang.citriage.log;

import java.util.List;
import java.util.Objects;

/** A diagnostic signature and the original findings that share it. */
public record FailureGroup(String id, DiagnosticCategory category, String signature, List<LogFinding> members) {
    public FailureGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(signature, "signature");
        members = List.copyOf(members);
        if (members.isEmpty() || members.stream().anyMatch(member -> member.category() != category)) {
            throw new IllegalArgumentException("A group requires members of its diagnostic category.");
        }
    }

    public int occurrenceCount() {
        return members.size();
    }

    public long distinctKnownTestCount() {
        return members.stream().flatMap(member -> member.testIdentity().stream()).distinct().count();
    }

    public long unknownTestOccurrenceCount() {
        return members.stream().filter(member -> member.testIdentity().isEmpty()).count();
    }
}
