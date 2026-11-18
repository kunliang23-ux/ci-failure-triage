package com.kunliang.citriage.report;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** An immutable snapshot of the bytes actually parsed, including their encoding and line endings. */
public final class ReportSource {
    private final String name;
    private final byte[] bytes;
    private final String sha256;

    public ReportSource(String name, byte[] bytes) {
        this.name = Objects.requireNonNull(name, "name");
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        try {
            this.sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime must provide SHA-256.", exception);
        }
    }

    public String name() {
        return name;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String sha256() {
        return sha256;
    }
}
