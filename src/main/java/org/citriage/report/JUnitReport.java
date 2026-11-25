package org.citriage.report;

import java.util.List;
import java.util.Objects;

/** The parsed observations and their original source snapshot. */
public record JUnitReport(ReportSource source, List<TestCaseResult> testCases) {
    public JUnitReport {
        Objects.requireNonNull(source, "source");
        testCases = List.copyOf(testCases);
    }

    public long count(TestOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return testCases.stream().filter(test -> test.outcome() == outcome).count();
    }
}
