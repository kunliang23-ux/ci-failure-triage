package com.kunliang.citriage.report;

import java.util.Objects;

/** A SAX diagnostic position at the end of an event; -1 means unavailable. */
public record SourceLocation(String sourceName, int line, int column) {
    public SourceLocation {
        Objects.requireNonNull(sourceName, "sourceName");
        if ((line != -1 && line < 1) || (column != -1 && column < 1)) {
            throw new IllegalArgumentException("XML positions must be positive or -1 (unknown).");
        }
    }

    public String display() {
        return sourceName + (line > 0 ? ":" + line + (column > 0 ? ":" + column : "") : "");
    }
}
