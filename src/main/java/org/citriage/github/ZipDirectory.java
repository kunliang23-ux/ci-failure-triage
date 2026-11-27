package org.citriage.github;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/** Counts actual central directory records before the ZIP reader allocates entries. */
final class ZipDirectory {
    private static final int END = 0x06054b50;
    private static final int ZIP64_END = 0x06064b50;
    private static final int ZIP64_LOCATOR = 0x07064b50;
    private static final int CENTRAL_FILE = 0x02014b50;
    private static final int END_BYTES = 22;
    private static final int CENTRAL_FILE_BYTES = 46;

    private ZipDirectory() {
    }

    static void check(Path archive, int maxEntries) throws IOException {
        try (var file = new RandomAccessFile(archive.toFile(), "r")) {
            long length = file.length();
            int tailLength = (int) Math.min(length, END_BYTES + 65535L);
            long tailOffset = length - tailLength;
            ByteBuffer tail = read(file, tailOffset, tailLength);
            int endIndex = findEnd(tail);
            long endOffset = tailOffset + endIndex;
            Directory directory = readDirectory(file, tail, endIndex, endOffset);
            if (directory.entries() > maxEntries) {
                throw overLimit(maxEntries);
            }
            if (directory.start() < 0 || directory.size() < 0 || directory.entries() < 0
                    || directory.start() != directory.end() - directory.size()) {
                throw invalid();
            }
            long end = directory.start() + directory.size();
            long position = directory.start();
            int entries = 0;
            while (position < end) {
                if (++entries > maxEntries) {
                    throw overLimit(maxEntries);
                }
                if (end - position < CENTRAL_FILE_BYTES) {
                    throw invalid();
                }
                ByteBuffer header = read(file, position, CENTRAL_FILE_BYTES);
                if (header.getInt(0) != CENTRAL_FILE) {
                    throw invalid();
                }
                position += CENTRAL_FILE_BYTES + u16(header, 28) + u16(header, 30) + u16(header, 32);
            }
            if (entries != directory.entries() || position != end) {
                throw invalid();
            }
        }
    }

    private static Directory readDirectory(RandomAccessFile file, ByteBuffer tail, int end, long offset)
            throws IOException {
        if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0
                || u16(tail, end + 8) != u16(tail, end + 10)) {
            throw invalid();
        }
        if (offset >= 20) {
            ByteBuffer locator = read(file, offset - 20, 20);
            if (locator.getInt(0) == ZIP64_LOCATOR) {
                long zip64Offset = locator.getLong(8);
                if (locator.getInt(4) != 0 || locator.getInt(16) != 1 || zip64Offset < 0
                        || zip64Offset > offset - 20 - 56) {
                    throw invalid();
                }
                ByteBuffer zip64 = read(file, zip64Offset, 56);
                if (zip64.getInt(0) != ZIP64_END || zip64.getInt(16) != 0 || zip64.getInt(20) != 0
                        || zip64.getLong(24) != zip64.getLong(32)
                        || zip64.getLong(4) != offset - 20 - zip64Offset - 12) {
                    throw invalid();
                }
                return new Directory(zip64.getLong(48), zip64.getLong(40), zip64.getLong(32), zip64Offset);
            }
        }
        return new Directory(Integer.toUnsignedLong(tail.getInt(end + 16)),
                Integer.toUnsignedLong(tail.getInt(end + 12)), u16(tail, end + 10), offset);
    }

    private static int findEnd(ByteBuffer tail) throws IOException {
        for (int position = tail.limit() - END_BYTES; position >= 0; position--) {
            if (tail.getInt(position) == END && position + END_BYTES + u16(tail, position + 20) == tail.limit()) {
                return position;
            }
        }
        throw invalid();
    }

    private static int u16(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static ByteBuffer read(RandomAccessFile file, long offset, int length) throws IOException {
        byte[] bytes = new byte[length];
        file.seek(offset);
        file.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static InvalidDirectory invalid() {
        return new InvalidDirectory("The ZIP central directory is malformed or split across disks.");
    }

    private static InvalidDirectory overLimit(int maxEntries) {
        return new InvalidDirectory("The ZIP exceeds the " + maxEntries + "-entry limit.");
    }

    static final class InvalidDirectory extends IOException {
        private static final long serialVersionUID = 1L;

        private InvalidDirectory(String message) {
            super(message);
        }
    }

    private record Directory(long start, long size, long entries, long end) {
    }
}
