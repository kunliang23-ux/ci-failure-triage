package com.kunliang.citriage.log;

import java.util.Objects;
import java.util.Optional;

import com.kunliang.citriage.report.TestIdentity;

/** A rule result, with source evidence and an optional explicitly reported test. */
public record LogFinding(DiagnosticCategory category, String ruleId, String explanation,
                         LogEvidence evidence, Optional<TestIdentity> testIdentity) {
    public LogFinding {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(testIdentity, "testIdentity");
    }
}
