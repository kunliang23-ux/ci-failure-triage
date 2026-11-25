package org.citriage.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.citriage.storage.RunMetadata.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunMetadataFileTest {
    @TempDir
    Path directory;

    @Test
    void acceptsBomLiteralUtf8AndOffsetTimestamps() throws Exception {
        RunMetadata metadata = read("\uFEFF" + """
                # Example execution
                ! Comment

                ENVIRONMENT = Café runner
                TEST_COMMAND=mvn -Dtest=example.Test test
                TEST_CONFIGURATION=checkout\\unit # literal value
                EXECUTED_AT=2025-01-02T04:00:00-05:00
                COMPLETED_AT=2025-01-02T09:05:00Z
                CONCLUSION=SUCCESS
                ATTEMPT=1
                """);
        assertEquals(Optional.of("Café runner"), metadata.value(Field.ENVIRONMENT));
        assertEquals(Optional.of("mvn -Dtest=example.Test test"), metadata.value(Field.TEST_COMMAND));
        assertEquals(Optional.of("checkout\\unit # literal value"), metadata.value(Field.TEST_CONFIGURATION));
        assertEquals(Optional.of(Instant.parse("2025-01-02T09:00:00Z")), metadata.instant(Field.EXECUTED_AT));
        assertEquals(Optional.of("SUCCESS"), metadata.value(Field.CONCLUSION));
        assertTrue(metadata.value(Field.OS).isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            NOT_A_KEY=yes | Unknown metadata key
            JOB demo | Expected KEY=value
            OS= | requires a nonblank known value
            OS=Unknown | Omit unknown fields
            CONCLUSION=running | Unsupported CONCLUSION
            EXECUTED_AT=2025-01-02T09:00:00 | timestamp with an offset
            COMPLETED_AT=2025-02-30T09:00:00Z | timestamp with an offset
            ATTEMPT=0 | positive 32-bit integer
            ATTEMPT=2147483648 | positive 32-bit integer
            """)
    void reportsInvalidKeysAndValuesWithTheirLine(String contents, String expected) {
        IOException error = assertThrows(IOException.class, () -> read("# fixture\n" + contents));
        assertTrue(error.getMessage().startsWith("Line 2: "));
        assertTrue(error.getMessage().contains(expected));
    }

    @Test
    void rejectsDuplicateKeysAndReversedTimestamps() {
        assertTrue(assertThrows(IOException.class, () -> read("OS=Linux\nOS = Linux\n"))
                .getMessage().contains("Line 2: Duplicate key OS"));
        assertTrue(assertThrows(IOException.class, () -> read("""
                EXECUTED_AT=2025-01-02T10:00:00Z
                COMPLETED_AT=2025-01-02T09:00:00Z
                """)).getMessage().contains("COMPLETED_AT precedes EXECUTED_AT"));
    }

    private RunMetadata read(String contents) throws IOException {
        return new RunMetadataFile().read(Files.writeString(directory.resolve("run.properties"), contents));
    }
}
