package nu.marginalia.index.forward;

import nu.marginalia.array.LongArray;
import nu.marginalia.array.LongArrayFactory;
import nu.marginalia.index.forward.spans.DocumentSpans;
import nu.marginalia.index.forward.spans.ForwardIndexSpansReader;
import nu.marginalia.model.id.UrlIdCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

import static nu.marginalia.index.forward.ForwardIndexParameters.*;

/** Reads the forward index.
 * <p/>
 * The forward index is constructed of a staggered array
 * called 'data' containing domains and document level metadata,
 * and a mapping between document identifiers to the index into the
 * data array.
 * <p/>
 * Since the total data is relatively small, this is kept in memory to
 * reduce the amount of disk thrashing.
 * <p/>
 * The metadata is a binary encoding of {@see nu.marginalia.idx.DocumentMetadata}
 */
public class ForwardIndexReader {
    private final LongArray ids;
    private final LongArray data;

    private final ForwardIndexSpansReader spansReader;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    public ForwardIndexReader(Path idsFile,
                              Path dataFile,
                              Path spansFile) throws IOException {
        if (!Files.exists(dataFile)) {
            logger.warn("Failed to create ForwardIndexReader, {} is absent", dataFile);
            ids = null;
            data = null;
            spansReader = null;
            return;
        }
        else if (!Files.exists(idsFile)) {
            logger.warn("Failed to create ForwardIndexReader, {} is absent", idsFile);
            ids = null;
            data = null;
            spansReader = null;
            return;
        }
        else if (!Files.exists(spansFile)) {
            logger.warn("Failed to create ForwardIndexReader, {} is absent", spansFile);
            ids = null;
            data = null;
            spansReader = null;
            return;
        }

        logger.info("Switching forward index");

        ids = loadIds(idsFile);
        data = loadData(dataFile);
        spansReader = new ForwardIndexSpansReader(spansFile);
    }

    private static LongArray loadIds(Path idsFile) throws IOException {
        return LongArrayFactory.mmapForReadingShared(idsFile);
    }

    private static LongArray loadData(Path dataFile) throws IOException {
        return LongArrayFactory.mmapForReadingShared(dataFile);
    }

    public long getDocMeta(long docId) {
        assert UrlIdCodec.getRank(docId) == 0 : "Forward Index Reader fed dirty reverse index id";

        long offset = idxForDoc(docId);
        if (offset < 0) return 0;

        return data.get(ENTRY_SIZE * offset + METADATA_OFFSET);
    }

    public int getHtmlFeatures(long docId) {
        assert UrlIdCodec.getRank(docId) == 0 : "Forward Index Reader fed dirty reverse index id";

        long offset = idxForDoc(docId);
        if (offset < 0) return 0;

        return (int) (data.get(ENTRY_SIZE * offset + FEATURES_OFFSET) & 0xFFFF_FFFFL);
    }

    public int getDocumentSize(long docId) {
        assert UrlIdCodec.getRank(docId) == 0 : "Forward Index Reader fed dirty reverse index id";

        long offset = idxForDoc(docId);
        if (offset < 0) return 0;

        return (int) (data.get(ENTRY_SIZE * offset + FEATURES_OFFSET) >>> 32L);
    }


    private int idxForDoc(long docId) {
        assert UrlIdCodec.getRank(docId) == 0 : "Forward Index Reader fed dirty reverse index id";

        long offset = ids.binarySearch(docId, 0, ids.size());

        if (offset >= ids.size() || offset < 0 || ids.get(offset) != docId) {
            if (getClass().desiredAssertionStatus()) {
                logger.warn("Could not find offset for doc {}", docId);
            }
            return -1;
        }

        return (int) offset;
    }

    public DocumentSpans getDocumentSpans(Arena arena, long docId) {
        long offset = idxForDoc(docId);
        if (offset < 0) return new DocumentSpans();

        long encodedOffset = data.get(ENTRY_SIZE * offset + SPANS_OFFSET);

        try {
            return spansReader.readSpans(arena, encodedOffset);
        }
        catch (IOException ex) {
            logger.error("Failed to read spans for doc " + docId, ex);
            return new DocumentSpans();
        }
    }


    public int totalDocCount() {
        return (int) ids.size();
    }

    public void close() {
        if (data != null)
            data.close();
    }

    public boolean isLoaded() {
        return data != null;
    }
}
