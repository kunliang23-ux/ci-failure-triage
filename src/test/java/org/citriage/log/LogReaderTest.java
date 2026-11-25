package org.citriage.log;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogReaderTest {
    @ParameterizedTest
    @MethodSource("lineEndings")
    void preservesPhysicalLinesWithoutAddingAnExtraLineAtEof(String text, List<LogLine> expected,
                                                             @TempDir Path directory) throws Exception {
        Path path = Files.writeString(directory.resolve("lines.log"), text, StandardCharsets.UTF_8);
        BuildLog log = new LogReader().read(path);

        assertEquals(expected, log.lines());
    }

    static Stream<Arguments> lineEndings() {
        return Stream.of(
                Arguments.of("", List.of()),
                Arguments.of("\n", List.of(new LogLine(1, "", "\n"))),
                Arguments.of("first\n\nthird\n", List.of(
                        new LogLine(1, "first", "\n"), new LogLine(2, "", "\n"), new LogLine(3, "third", "\n"))),
                Arguments.of("first\r\n\r\nthird", List.of(
                        new LogLine(1, "first", "\r\n"), new LogLine(2, "", "\r\n"), new LogLine(3, "third", ""))),
                Arguments.of("first\r\nsecond\nupdate\roverwrite", List.of(
                        new LogLine(1, "first", "\r\n"), new LogLine(2, "second", "\n"),
                        new LogLine(3, "update\roverwrite", ""))));
    }

    @Test
    void preservesUtf8SnapshotAndHashWithoutChangingTheFile(@TempDir Path directory) throws Exception {
        String text = "\uFEFFcaf\u00e9 \u2615\r\n\r\nlast line";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Path path = Files.write(directory.resolve("original evidence.log"), bytes);
        BuildLog log = new LogReader().read(path);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        byte[] copy = log.source().bytes();
        copy[0] = 0;

        assertAll(
                () -> assertArrayEquals(bytes, Files.readAllBytes(path)),
                () -> assertArrayEquals(bytes, log.source().bytes()),
                () -> assertEquals(expectedHash, log.source().sha256()),
                () -> assertEquals(path.toAbsolutePath().normalize().toString(), log.source().name()),
                () -> assertEquals(text, log.text()),
                () -> assertEquals("\uFEFFcaf\u00e9 \u2615", log.lines().get(0).text()),
                () -> assertThrows(UnsupportedOperationException.class, () -> log.lines().clear()));
    }

    @Test
    void whitespaceAndUtf8BomAloneAreBlankEvidence(@TempDir Path directory) throws Exception {
        Path path = Files.writeString(directory.resolve("blank.log"), "\uFEFF\r\n\t\n");
        BuildLog log = new LogReader().read(path);

        assertTrue(log.isBlank());
        assertEquals(2, log.lines().size());
    }

    @Test
    void rejectsInvalidUtf8InsteadOfReplacingEvidenceBytes(@TempDir Path directory) throws Exception {
        Path path = Files.write(directory.resolve("bad-encoding.log"), new byte[]{(byte) 0xc3, 0x28});

        assertThrows(CharacterCodingException.class, () -> new LogReader().read(path));
    }
}
