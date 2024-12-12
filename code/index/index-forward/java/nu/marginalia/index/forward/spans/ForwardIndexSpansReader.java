package nu.marginalia.index.forward.spans;

import nu.marginalia.sequence.VarintCodedSequence;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@SuppressWarnings("preview")
public class ForwardIndexSpansReader implements AutoCloseable {
    private final FileChannel spansFileChannel;

    public ForwardIndexSpansReader(Path spansFile) throws IOException {
        this.spansFileChannel = (FileChannel) Files.newByteChannel(spansFile, StandardOpenOption.READ);
    }

    public DocumentSpans readSpans(Arena arena, long encodedOffset) throws IOException {
        // Decode the size and offset from the encoded offset
        long size = SpansCodec.decodeSize(encodedOffset);
        long offset = SpansCodec.decodeStartOffset(encodedOffset);

        // Allocate a buffer from the arena
        var buffer = arena.allocate(size).asByteBuffer();
        buffer.clear();
        while (buffer.hasRemaining()) {
            spansFileChannel.read(buffer, offset + buffer.position());
        }
        buffer.flip();

        // Read the number of spans in the document
        int count = buffer.get();

        DocumentSpans ret = new DocumentSpans();

        // Decode each span
        while (count-- > 0) {
            byte code = buffer.get();
            short len = buffer.getShort();

            ByteBuffer data = buffer.slice(buffer.position(), len);
            ret.accept(code, new VarintCodedSequence(data));

            // Reset the buffer position to the end of the span
            buffer.position(buffer.position() + len);
        }

        return ret;
    }

    @Override
    public void close() throws IOException {
        spansFileChannel.close();
    }

}
