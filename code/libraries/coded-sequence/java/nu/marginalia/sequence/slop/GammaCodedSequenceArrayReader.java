package nu.marginalia.sequence.slop;

import nu.marginalia.sequence.GammaCodedSequence;
import nu.marginalia.slop.column.ColumnReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public interface GammaCodedSequenceArrayReader extends AutoCloseable, ColumnReader {
    /** Read the next gamma-coded sequence from the column.  Unlike most other
     * readers, this method requires an intermediate buffer to use for reading
     * the sequence.  As this buffer typically needs to be fairly large to accommodate
     * the largest possible sequence, it is not practical to allocate a new buffer
     * for each call to this method.  Instead, the caller should allocate a buffer
     * once and reuse it for each call to this method.
     *
     * @return The next gamma-coded sequence.
     */
    List<GammaCodedSequence> get() throws IOException;

    /** Read just the data portion of the next gamma-coded sequence from the column.
     * This method is useful when the caller is only interested in the data portion
     * of the sequence and does not want to decode the values.
     *
     * @param workArea A buffer to use for reading the data.
     * @return slices of the work buffer containing the data.
     */
    List<ByteBuffer> getData(ByteBuffer workArea) throws IOException;

    void close() throws IOException;
}
