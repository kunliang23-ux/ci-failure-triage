package org.citriage.report;

/** A rejected report still retains the original source for investigation. */
public final class ReportParseException extends Exception {
    private static final long serialVersionUID = 1L;

    private final ReportSource source;
    private final SourceLocation location;

    public ReportParseException(ReportSource source, SourceLocation location, String message, Throwable cause) {
        super(location.display() + ": " + message, cause);
        this.source = source;
        this.location = location;
    }

    public ReportSource source() {
        return source;
    }

    public SourceLocation location() {
        return location;
    }
}
