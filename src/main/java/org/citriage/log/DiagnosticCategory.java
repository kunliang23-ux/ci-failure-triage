package org.citriage.log;

/** Categories of observed diagnostics. */
public enum DiagnosticCategory {
    ASSERTION_ERROR,
    MISSING_DEPENDENCY,
    COMPILATION_ERROR,
    TIMEOUT,
    UNKNOWN
}
