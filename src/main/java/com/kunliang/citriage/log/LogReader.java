package com.kunliang.citriage.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import com.kunliang.citriage.report.ReportSource;

/** Reads a local UTF-8 snapshot independently of classification and presentation. */
public final class LogReader {
    public BuildLog read(Path path) throws IOException {
        Path sourcePath = path.toAbsolutePath().normalize();
        if (!Files.readAttributes(sourcePath, BasicFileAttributes.class).isRegularFile()) {
            throw new IOException("Expected a regular file.");
        }
        byte[] bytes = Files.readAllBytes(sourcePath);
        String text = decodeUtf8(bytes);
        return new BuildLog(new ReportSource(sourcePath.toString(), bytes), text);
    }

    /** Opens a saved snapshot without touching its original path. */
    public BuildLog decode(ReportSource source) throws CharacterCodingException {
        return new BuildLog(source, decodeUtf8(source.bytes()));
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        // The decoder reports invalid input instead of replacing bytes with U+FFFD.
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }
}
