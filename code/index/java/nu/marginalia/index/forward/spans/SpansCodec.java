package nu.marginalia.index.forward.spans;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SpansCodec {
    public static int MAGIC_INT = 0xF000F000;
    public static int FOOTER_SIZE = 8;

    public enum SpansCodecVersion {
        PLAIN
    }

    public static long encode(long startOffset, long size) {
        assert size < 0x1000_0000L : "Size must be less than 2^28";

        return startOffset << 28 | (size & 0xFFF_FFFFL);
    }

    public static long decodeStartOffset(long encoded) {
        return encoded >>> 28;
    }

    public static int decodeSize(long encoded) {
        return (int) (encoded & 0x0FFF_FFFFL);
    }

    public static ByteBuffer createSpanFilesFooter(SpansCodecVersion version, int padSize) {
        if (padSize < FOOTER_SIZE) {
            padSize += 4096;
        }

        ByteBuffer footer = ByteBuffer.allocate(padSize);
        footer.position(padSize - FOOTER_SIZE);
        footer.putInt(SpansCodec.MAGIC_INT);
        footer.put((byte) version.ordinal());
        footer.put((byte) 0);
        footer.put((byte) 0);
        footer.put((byte) 0);
        footer.flip();
        return footer;
    }

    public static int parseSpanFilesFooter(Path spansFile) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(FOOTER_SIZE);

        try (var fc = FileChannel.open(spansFile, StandardOpenOption.READ)) {
            if (fc.size() < FOOTER_SIZE) return 0;
            fc.read(buffer, fc.size() - buffer.capacity());
            buffer.flip();
            int magic = buffer.getInt();
            if (magic != MAGIC_INT) {
                return 0;
            }
            return buffer.get();
        }

    }
}
