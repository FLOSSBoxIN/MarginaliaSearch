package nu.marginalia.index;

import nu.marginalia.array.buffer.LongQueryBuffer;
import nu.marginalia.btree.BTreeReader;
import nu.marginalia.index.query.EntrySource;

import static java.lang.Math.min;

public class ReverseIndexEntrySource implements EntrySource {
    private final BTreeReader reader;

    int pos;
    int endOffset;

    final int entrySize;
    private final long wordId;

    public ReverseIndexEntrySource(BTreeReader reader,
                                   int entrySize,
                                   long wordId) {
        this.reader = reader;
        this.entrySize = entrySize;
        this.wordId = wordId;

        pos = 0;
        endOffset = pos + entrySize * reader.numEntries();
    }

    @Override
    public void skip(int n) {
        pos += n;
    }

    @Override
    public void read(LongQueryBuffer buffer) {
        buffer.end = min(buffer.end, endOffset - pos);
        reader.readData(buffer.data, buffer.end, pos);
        pos += buffer.end;

        destagger(buffer);
        buffer.uniq();
    }

    private void destagger(LongQueryBuffer buffer) {
        if (entrySize == 1)
            return;

        for (int ri = entrySize, wi=1; ri < buffer.end ; ri+=entrySize, wi++) {
            buffer.data.set(wi, buffer.data.get(ri));
        }

        buffer.end /= entrySize;
    }

    @Override
    public boolean hasMore() {
        return pos < endOffset;
    }


    @Override
    public String indexName() {
        return "Full:" + Long.toHexString(wordId);
    }
}
