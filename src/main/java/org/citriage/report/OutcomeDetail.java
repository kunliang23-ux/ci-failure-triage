package org.citriage.report;

import java.util.Objects;
import java.util.Optional;

/** A failure, error, or skipped marker. Text is XML-decoded, but is never trimmed or grouped. */
public record OutcomeDetail(TestOutcome outcome, Optional<String> type, Optional<String> message,
                            String text, SourceLocation location) {
    public OutcomeDetail {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(location, "location");
        if (outcome == TestOutcome.PASSED) {
            throw new IllegalArgumentException("A passed test has no outcome marker.");
        }
    }
}
