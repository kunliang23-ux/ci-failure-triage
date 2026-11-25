package org.citriage.report;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One execution in document order; repeated names remain separate executions. */
public record TestCaseResult(List<String> suitePath, TestIdentity identity, Optional<BigDecimal> durationSeconds,
                             List<OutcomeDetail> details, SourceLocation location) {
    public TestCaseResult {
        suitePath = List.copyOf(suitePath);
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(durationSeconds, "durationSeconds");
        details = List.copyOf(details);
        Objects.requireNonNull(location, "location");
        durationSeconds.ifPresent(duration -> {
            if (duration.signum() < 0) {
                throw new IllegalArgumentException("A testcase duration cannot be negative.");
            }
        });
        if (details.size() > 1 && details.stream().anyMatch(detail -> detail.outcome() == TestOutcome.SKIPPED)) {
            throw new IllegalArgumentException("A skipped marker cannot be repeated or mixed with failures/errors.");
        }
    }

    /** Retains the initial importer's constructor for callers without duration metadata. */
    public TestCaseResult(List<String> suitePath, Optional<String> className, String name,
                          List<OutcomeDetail> details, SourceLocation location) {
        this(suitePath, new TestIdentity(className, name), Optional.empty(), details, location);
    }

    public Optional<String> className() {
        return identity.className();
    }

    public String name() {
        return identity.testName();
    }

    public TestOutcome outcome() {
        if (details.stream().anyMatch(detail -> detail.outcome() == TestOutcome.ERROR)) {
            return TestOutcome.ERROR;
        }
        if (details.stream().anyMatch(detail -> detail.outcome() == TestOutcome.FAILED)) {
            return TestOutcome.FAILED;
        }
        return details.isEmpty() ? TestOutcome.PASSED : TestOutcome.SKIPPED;
    }
}
