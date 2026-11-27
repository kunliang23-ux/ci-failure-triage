package org.citriage.github;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/** Owns only the scratch files for a single, validated bundle. */
final class BundleArchive implements AutoCloseable {
    static final String MANIFEST = "manifest.json";
    static final String BUILD_LOG = "build.log";
    static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final Pattern JUNIT_PATH = Pattern.compile("reports/(?:surefire|failsafe)/"
            + "TEST-([\\p{L}\\p{N}_$-]+\\.)*[\\p{L}\\p{N}_$-]+\\.xml");
    private static final Set<String> DIRECTORIES = Set.of("reports/", "reports/surefire/", "reports/failsafe/");

    private final Path root;

    BundleArchive() throws IOException {
        try {
            root = Files.createTempDirectory("ci-triage-github-");
        } catch (IOException exception) {
            throw new IOException("Cannot create temporary storage for the GitHub artifact.", exception);
        }
    }

    Path zipPath() {
        return root.resolve("artifact.zip");
    }

    Map<String, Path> extract(Optional<String> expectedDigest) throws IOException {
        try {
            if (Files.size(zipPath()) > MAX_DOWNLOAD_BYTES) {
                throw new InvalidBundle("The downloaded ZIP exceeds the 32 MiB limit.");
            }
            if (expectedDigest.isPresent() && !expectedDigest.get().equals("sha256:" + sha256(zipPath()))) {
                throw new InvalidBundle("Downloaded ZIP SHA-256 does not match the GitHub artifact digest.");
            }
            try {
                ZipDirectory.check(zipPath(), MAX_ENTRIES);
            } catch (ZipDirectory.InvalidDirectory exception) {
                throw new InvalidBundle(exception.getMessage());
            }
            Path destination = Files.createDirectory(root.resolve("evidence"));
            try (ZipFile zip = ZipFile.builder().setPath(zipPath()).setUseUnicodeExtraFields(false).get()) {
                return extractEntries(zip, destination);
            }
        } catch (InvalidBundle exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IOException("Cannot read the downloaded ZIP. Check that it is a valid triage bundle.", exception);
        }
    }

    private static Map<String, Path> extractEntries(ZipFile zip, Path destination) throws IOException {
        var entries = zip.getEntries();
        var seen = new HashSet<String>();
        var files = new HashMap<String, Path>();
        long total = 0;
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            String name = validatedName(zip, entry);
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new InvalidBundle("The ZIP contains duplicate or case-colliding entries.");
            }
            if (seen.size() > MAX_ENTRIES) {
                throw new InvalidBundle("The ZIP exceeds the 256-entry limit.");
            }
            int limit = MANIFEST.equals(name) ? MAX_MANIFEST_BYTES : MAX_FILE_BYTES;
            if (entry.getSize() < 0 || entry.getSize() > limit || (total += entry.getSize()) > MAX_TOTAL_BYTES) {
                throw new InvalidBundle("The ZIP exceeds the manifest, entry, or total uncompressed size limit.");
            }
            if (!entry.isDirectory()) {
                Path path = destination.resolve(name).normalize();
                if (!path.startsWith(destination)) {
                    throw new InvalidBundle("The ZIP contains an unsafe entry path.");
                }
                Files.createDirectories(path.getParent());
                extractFile(zip, entry, path);
                files.put(name, path);
            }
        }
        return Map.copyOf(files);
    }

    private static String validatedName(ZipFile zip, ZipArchiveEntry entry) throws InvalidBundle {
        String name = entry.getName();
        if (!name.equals(new String(entry.getRawName(), StandardCharsets.UTF_8))
                || !allowed(name, entry.isDirectory())) {
            throw new InvalidBundle("The ZIP contains an unsafe or unexpected entry path.");
        }
        int unixType = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        int expectedType = entry.isDirectory() ? UnixStat.DIR_FLAG : UnixStat.FILE_FLAG;
        if (!zip.canReadEntryData(entry) || entry.getGeneralPurposeBit().usesEncryption()
                || (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)
                || (unixType != 0 && unixType != expectedType)
                || (entry.getPlatform() == ZipArchiveEntry.PLATFORM_FAT
                    && (entry.getExternalAttributes() & 0x400) != 0) // Windows reparse point
                || (entry.isDirectory() && entry.getSize() != 0)) {
            throw new InvalidBundle("The ZIP contains an unsupported entry type, compression, or encryption.");
        }
        return name;
    }

    private static boolean allowed(String name, boolean directory) {
        return directory ? DIRECTORIES.contains(name)
                : MANIFEST.equals(name) || BUILD_LOG.equals(name) || isJUnitPath(name);
    }

    static boolean isJUnitPath(String name) {
        return name.length() <= 240 && JUNIT_PATH.matcher(name).matches();
    }

    private static void extractFile(ZipFile zip, ZipArchiveEntry entry, Path destination) throws IOException {
        var crc = new CRC32();
        long size = 0;
        try (var input = zip.getInputStream(entry);
             var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > entry.getSize()) {
                    throw new InvalidBundle("A ZIP entry expands beyond its declared uncompressed size.");
                }
                crc.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
            throw new InvalidBundle("A ZIP entry has an invalid size or CRC.");
        }
    }

    private static String sha256(Path path) throws IOException {
        try (var input = new DigestInputStream(Files.newInputStream(path), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime must provide SHA-256.", exception);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IOException("Cannot remove temporary GitHub import files.", exception);
        }
    }

    private static final class InvalidBundle extends IOException {
        private static final long serialVersionUID = 1L;

        private InvalidBundle(String message) {
            super(message);
        }
    }
}
