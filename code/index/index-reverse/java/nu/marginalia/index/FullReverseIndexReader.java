package nu.marginalia.index;

import nu.marginalia.array.LongArray;
import nu.marginalia.array.LongArrayFactory;
import nu.marginalia.array.pool.BufferPool;
import nu.marginalia.btree.BTreeReader;
import nu.marginalia.btree.PoolingBTreeReader;
import nu.marginalia.index.positions.PositionsFileReader;
import nu.marginalia.index.positions.TermData;
import nu.marginalia.index.query.EmptyEntrySource;
import nu.marginalia.index.query.EntrySource;
import nu.marginalia.index.query.ReverseIndexRejectFilter;
import nu.marginalia.index.query.ReverseIndexRetainFilter;
import nu.marginalia.index.query.filter.QueryFilterLetThrough;
import nu.marginalia.index.query.filter.QueryFilterNoPass;
import nu.marginalia.index.query.filter.QueryFilterStepIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class FullReverseIndexReader {
    private final LongArray words;
    private final LongArray documents;
    private final long wordsDataOffset;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BTreeReader wordsBTreeReader;
    private final String name;

    private final PositionsFileReader positionsFileReader;

    private final BufferPool[] indexPools;
    private final BufferPool[] dataPools;

    private final long[] poolOffsets;
    private final AtomicInteger poolIdx = new AtomicInteger();

    public FullReverseIndexReader(String name,
                                  Path words,
                                  Path documents,
                                  PositionsFileReader positionsFileReader) throws IOException {
        this.name = name;

        this.positionsFileReader = positionsFileReader;

        if (!Files.exists(words) || !Files.exists(documents)) {
            this.words = null;
            this.documents = null;
            this.wordsBTreeReader = null;
            this.wordsDataOffset = -1;
            this.dataPools = null;
            this.indexPools = null;
            this.poolOffsets = null;
            return;
        }

        logger.info("Switching reverse index");

        this.words = LongArrayFactory.mmapForReadingShared(words);
        this.documents = LongArrayFactory.mmapForReadingShared(documents);

        indexPools = new BufferPool[64];
        dataPools = new BufferPool[64];
        poolOffsets = new long[64];

        for (int i = 0; i < 64; i++) {
            indexPools[i] = new BufferPool(documents, 8 * ReverseIndexParameters.wordsBTreeContext.pageSize(), 32);
            dataPools[i] = new BufferPool(documents, 8 * ReverseIndexParameters.wordsBTreeContext.pageSize() * ReverseIndexParameters.wordsBTreeContext.entrySize, 32);
        }

        wordsBTreeReader = new BTreeReader(this.words, ReverseIndexParameters.wordsBTreeContext, 0);
        wordsDataOffset = wordsBTreeReader.getHeader().dataOffsetLongs();

        if (getClass().desiredAssertionStatus()) {
            if (Boolean.getBoolean("index-self-test")) {
                Executors.newSingleThreadExecutor().execute(this::selfTest);
            }
        }
    }

    private void selfTest() {
        logger.info("Running self test program");

        long wordsDataSize = wordsBTreeReader.getHeader().numEntries() * 2L;
        var wordsDataRange = words.range(wordsDataOffset, wordsDataOffset + wordsDataSize);

//        ReverseIndexSelfTest.runSelfTest1(wordsDataRange, wordsDataSize);
//        ReverseIndexSelfTest.runSelfTest2(wordsDataRange, documents);
//        ReverseIndexSelfTest.runSelfTest3(wordsDataRange, wordsBTreeReader);
//        ReverseIndexSelfTest.runSelfTest4(wordsDataRange, documents);
        ReverseIndexSelfTest.runSelfTest5(wordsDataRange, wordsBTreeReader);
        ReverseIndexSelfTest.runSelfTest6(wordsDataRange, documents);
    }


    /** Calculate the offset of the word in the documents.
     * If the return-value is negative, the term does not exist
     * in the index.
     */
    long wordOffset(long termId) {
        long idx = wordsBTreeReader.findEntry(termId);

        if (idx < 0)
            return -1L;

        return words.get(wordsDataOffset + idx + 1);
    }

    public EntrySource documents(long termId) {
        if (null == words) {
            logger.warn("Reverse index is not ready, dropping query");
            return new EmptyEntrySource();
        }

        long offset = wordOffset(termId);

        if (offset < 0) // No documents
            return new EmptyEntrySource();

        return new FullIndexEntrySource(name, getReader(offset), 2, termId);
    }

    /** Create a filter step requiring the specified termId to exist in the documents */
    public QueryFilterStepIf also(long termId) {
        long offset = wordOffset(termId);

        if (offset < 0) // No documents
            return new QueryFilterNoPass();

        return new ReverseIndexRetainFilter(getReader(offset), name, termId);
    }

    /** Create a filter step requiring the specified termId to be absent from the documents */
    public QueryFilterStepIf not(long termId) {
        long offset = wordOffset(termId);

        if (offset < 0) // No documents
            return new QueryFilterLetThrough();

        return new ReverseIndexRejectFilter(getReader(offset));
    }

    /** Return the number of documents with the termId in the index */
    public int numDocuments(long termId) {
        long offset = wordOffset(termId);

        if (offset < 0)
            return 0;

        return getReader(offset).numEntries();
    }

    /** Create a BTreeReader for the document offset associated with a termId */
    private PoolingBTreeReader getReader(long offset) {
        int idx = -1;
        for (int i = 0; i < indexPools.length; i++) {
            if (poolOffsets[i] == offset) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = poolIdx.incrementAndGet() % poolOffsets.length;
            poolOffsets[idx] = offset;
        }

        return new PoolingBTreeReader(
                indexPools[idx],
                dataPools[idx],
                ReverseIndexParameters.fullDocsBTreeContext,
                offset);
    }

    public TermData[] getTermData(Arena arena,
                                  long termId,
                                  long[] docIds)
    {
        var ret = new TermData[docIds.length];

        long offset = wordOffset(termId);

        if (offset < 0) {
            // This is likely a bug in the code, but we can't throw an exception here
            logger.debug("Missing offset for word {}", termId);
            return ret;
        }

        var reader = getReader(offset);

        // Read the size and offset of the position data
        var offsets = reader.queryData(docIds, 1);

        return positionsFileReader.getTermData(arena, offsets);
    }

    public void close() {

        try {

            if (indexPools != null) {
                for (var pool : indexPools) {
                    pool.close();
                }
            }
            if (dataPools != null) {
                for (var pool : dataPools) {
                    pool.close();
                }
            }
        }
        catch (Exception e) {
            logger.warn("Error while closing bufferPool", e);
        }

        if (documents != null)
            documents.close();

        if (words != null)
            words.close();

        if (positionsFileReader != null) {
            try {
                positionsFileReader.close();
            } catch (IOException e) {
                logger.error("Failed to close positions file reader", e);
            }
        }
    }

}
