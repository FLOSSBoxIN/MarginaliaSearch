package nu.marginalia.btree.model;

import nu.marginalia.array.LongArray;

public record BTreeHeader(int layers, int numEntries, long indexOffsetLongs, long dataOffsetLongs) {
    public BTreeHeader {
        assert (layers >= 0);
        assert (numEntries >= 0);
        assert (indexOffsetLongs >= 0);
        assert (dataOffsetLongs >= 0);
        assert (dataOffsetLongs >= indexOffsetLongs);
    }

    public static final int BTreeHeaderSizeLongs = 3;

    public BTreeHeader(long a, long b, long c) {
        this((int)(a >>> 32), (int)(a & 0xFFFF_FFFFL), b, c);
    }
    public BTreeHeader(LongArray array, long offset) {
        this(array.get(offset), array.get(offset+1), array.get(offset+2));
    }

    public long relativeIndexLayerOffset(BTreeContext ctx, int n) {
        long offset = 0;

        for (int i = n+1; i < layers; i++) {
            offset += ctx.indexLayerSize( numEntries, i);
        }

        return offset;
    }

    public long[] getRelativeLayerOffsets(BTreeContext ctx) {
        long[] layerOffsets = new long[layers()];
        for (int i = 0; i < layers(); i++) {
            layerOffsets[i] = relativeIndexLayerOffset(ctx, i);
        }
        return layerOffsets;
    }

}
