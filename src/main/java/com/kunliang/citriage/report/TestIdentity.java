package com.kunliang.citriage.report;

import java.util.Objects;
import java.util.Optional;

/** A reported class/test name pair; repeated executions may share an identity. */
public record TestIdentity(Optional<String> className, String testName) {
    public TestIdentity {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(testName, "testName");
        if (testName.isBlank()) {
            throw new IllegalArgumentException("A testcase must have a nonblank name.");
        }
    }

    public String displayName() {
        return className.map(name -> name + "#").orElse("") + testName;
    }
}
