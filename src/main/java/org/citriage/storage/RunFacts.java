package org.citriage.storage;

/** Stored eligibility facts that can be read without opening any source snapshots. */
public record RunFacts(String id, RunMetadata metadata, long junitExecutions, long passedExecutions,
                       long failedOrErroredExecutions, long logFindings) {
}
