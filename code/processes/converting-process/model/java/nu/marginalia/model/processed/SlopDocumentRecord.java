package nu.marginalia.model.processed;

import lombok.Builder;
import nu.marginalia.sequence.GammaCodedSequence;
import nu.marginalia.sequence.slop.GammaCodedSequenceArrayColumn;
import nu.marginalia.sequence.slop.GammaCodedSequenceArrayReader;
import nu.marginalia.sequence.slop.GammaCodedSequenceArrayWriter;
import nu.marginalia.slop.ColumnTypes;
import nu.marginalia.slop.column.array.ByteArrayColumnReader;
import nu.marginalia.slop.column.array.ByteArrayColumnWriter;
import nu.marginalia.slop.column.array.ObjectArrayColumnReader;
import nu.marginalia.slop.column.array.ObjectArrayColumnWriter;
import nu.marginalia.slop.column.dynamic.VarintColumnReader;
import nu.marginalia.slop.column.dynamic.VarintColumnWriter;
import nu.marginalia.slop.column.primitive.*;
import nu.marginalia.slop.column.string.EnumColumnReader;
import nu.marginalia.slop.column.string.StringColumnReader;
import nu.marginalia.slop.column.string.StringColumnWriter;
import nu.marginalia.slop.desc.ColumnDesc;
import nu.marginalia.slop.desc.SlopTable;
import nu.marginalia.slop.desc.StorageType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record SlopDocumentRecord(
        String domain,
        String url,
        int ordinal,
        String state,
        String stateReason,
        String title,
        String description,
        int htmlFeatures,
        String htmlStandard,
        int length,
        long hash,
        float quality,
        long documentMetadata,
        Integer pubYear,
        List<String> words,
        byte[] metas,
        List<GammaCodedSequence> positions,
        byte[] spanCodes,
        List<GammaCodedSequence> spans
) {

    public SlopDocumentRecord {
        if (spanCodes.length != spans.size())
            throw new IllegalArgumentException("Span codes and spans must have the same length");
        if (metas.length != words.size() || metas.length != positions.size())
            throw new IllegalArgumentException("Metas, words and positions must have the same length");
    }

    @Builder
    public record KeywordsProjection(
            String domain,
            int ordinal,
            int htmlFeatures,
            long documentMetadata,
            int length,
            List<String> words,
            byte[] metas,
            List<GammaCodedSequence> positions,
            byte[] spanCodes,
            List<GammaCodedSequence> spans)
    {
        // Override the equals method since records don't generate default equals that deal with array fields properly
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof KeywordsProjection that)) return false;

            return length == that.length && ordinal == that.ordinal && htmlFeatures == that.htmlFeatures && documentMetadata == that.documentMetadata && Arrays.equals(metas, that.metas) && Objects.equals(domain, that.domain) && Arrays.equals(spanCodes, that.spanCodes) && Objects.equals(words, that.words) && Objects.equals(spans, that.spans) && Objects.equals(positions, that.positions);
        }

        @Override
        public int hashCode() {
            int result = Objects.hashCode(domain);
            result = 31 * result + ordinal;
            result = 31 * result + htmlFeatures;
            result = 31 * result + Long.hashCode(documentMetadata);
            result = 31 * result + length;
            result = 31 * result + Objects.hashCode(words);
            result = 31 * result + Arrays.hashCode(metas);
            result = 31 * result + Objects.hashCode(positions);
            result = 31 * result + Arrays.hashCode(spanCodes);
            result = 31 * result + Objects.hashCode(spans);
            return result;
        }
    }

    public record MetadataProjection(
            String domain,
            String url,
            int ordinal,
            String title,
            String description,
            int htmlFeatures,
            String htmlStandard,
            int length,
            long hash,
            float quality,
            Integer pubYear
    ) {

    }

    // Basic information
    private static final ColumnDesc<StringColumnReader, StringColumnWriter> domainsColumn = new ColumnDesc<>("domain", ColumnTypes.TXTSTRING, StorageType.GZIP);
    private static final ColumnDesc<StringColumnReader, StringColumnWriter> urlsColumn = new ColumnDesc<>("url", ColumnTypes.TXTSTRING, StorageType.GZIP);
    private static final ColumnDesc<VarintColumnReader, VarintColumnWriter> ordinalsColumn = new ColumnDesc<>("ordinal", ColumnTypes.VARINT_LE, StorageType.PLAIN);
    private static final ColumnDesc<EnumColumnReader, StringColumnWriter> statesColumn = new ColumnDesc<>("state", ColumnTypes.ENUM_LE, StorageType.PLAIN);
    private static final ColumnDesc<StringColumnReader, StringColumnWriter> stateReasonsColumn = new ColumnDesc<>("stateReason", ColumnTypes.TXTSTRING, StorageType.GZIP);

    // Document metadata
    private static final ColumnDesc<StringColumnReader, StringColumnWriter> titlesColumn = new ColumnDesc<>("title", ColumnTypes.STRING, StorageType.GZIP);
    private static final ColumnDesc<StringColumnReader, StringColumnWriter> descriptionsColumn = new ColumnDesc<>("description", ColumnTypes.STRING, StorageType.GZIP);
    private static final ColumnDesc<EnumColumnReader, StringColumnWriter> htmlStandardsColumn = new ColumnDesc<>("htmlStandard", ColumnTypes.ENUM_LE, StorageType.GZIP);
    private static final ColumnDesc<IntColumnReader, IntColumnWriter> htmlFeaturesColumn = new ColumnDesc<>("htmlFeatures", ColumnTypes.INT_LE, StorageType.PLAIN);
    private static final ColumnDesc<IntColumnReader, IntColumnWriter> lengthsColumn = new ColumnDesc<>("length", ColumnTypes.INT_LE, StorageType.PLAIN);
    private static final ColumnDesc<IntColumnReader, IntColumnWriter> pubYearColumn = new ColumnDesc<>("pubYear", ColumnTypes.INT_LE, StorageType.PLAIN);
    private static final ColumnDesc<LongColumnReader, LongColumnWriter> hashesColumn = new ColumnDesc<>("hash", ColumnTypes.LONG_LE, StorageType.PLAIN);
    private static final ColumnDesc<FloatColumnReader, FloatColumnWriter> qualitiesColumn = new ColumnDesc<>("quality", ColumnTypes.FLOAT_LE, StorageType.PLAIN);
    private static final ColumnDesc<LongColumnReader, LongColumnWriter> domainMetadata = new ColumnDesc<>("domainMetadata", ColumnTypes.LONG_LE, StorageType.PLAIN);

    // Keyword-level columns, these are enumerated by the counts column
    private static final ColumnDesc<ObjectArrayColumnReader<String>, ObjectArrayColumnWriter<String>> keywordsColumn = new ColumnDesc<>("keywords", ColumnTypes.STRING_ARRAY, StorageType.ZSTD);
    private static final ColumnDesc<ByteArrayColumnReader, ByteArrayColumnWriter> termMetaColumn = new ColumnDesc<>("termMetadata", ColumnTypes.BYTE_ARRAY, StorageType.ZSTD);
    private static final ColumnDesc<GammaCodedSequenceArrayReader, GammaCodedSequenceArrayWriter> termPositionsColumn = new ColumnDesc<>("termPositions", GammaCodedSequenceArrayColumn.TYPE, StorageType.ZSTD);

    // Spans columns
    private static final ColumnDesc<ByteArrayColumnReader, ByteArrayColumnWriter> spanCodesColumn = new ColumnDesc<>("spanCodes", ColumnTypes.BYTE_ARRAY, StorageType.ZSTD);
    private static final ColumnDesc<GammaCodedSequenceArrayReader, GammaCodedSequenceArrayWriter> spansColumn = new ColumnDesc<>("spans", GammaCodedSequenceArrayColumn.TYPE, StorageType.ZSTD);

    public static class KeywordsProjectionReader extends SlopTable {
        private final StringColumnReader domainsReader;
        private final VarintColumnReader ordinalsReader;
        private final IntColumnReader htmlFeaturesReader;
        private final LongColumnReader domainMetadataReader;
        private final IntColumnReader lengthsReader;

        private final ObjectArrayColumnReader<String> keywordsReader;
        private final ByteArrayColumnReader termMetaReader;
        private final GammaCodedSequenceArrayReader termPositionsReader;

        private final ByteArrayColumnReader spanCodesReader;
        private final GammaCodedSequenceArrayReader spansReader;

        public KeywordsProjectionReader(SlopPageRef<SlopDocumentRecord> pageRef) throws IOException {
            this(pageRef.baseDir(), pageRef.page());
        }

        public KeywordsProjectionReader(Path baseDir, int page) throws IOException {
            super(page);
            domainsReader = domainsColumn.open(this, baseDir);
            ordinalsReader = ordinalsColumn.open(this, baseDir);
            htmlFeaturesReader = htmlFeaturesColumn.open(this, baseDir);
            domainMetadataReader = domainMetadata.open(this, baseDir);
            lengthsReader = lengthsColumn.open(this, baseDir);

            keywordsReader = keywordsColumn.open(this, baseDir);
            termMetaReader = termMetaColumn.open(this, baseDir);
            termPositionsReader = termPositionsColumn.open(this, baseDir);

            spanCodesReader = spanCodesColumn.open(this, baseDir);
            spansReader = spansColumn.open(this, baseDir);
        }

        public boolean hasMore() throws IOException {
            return domainsReader.hasRemaining();
        }

        @Nullable
        public KeywordsProjection next() throws IOException {
            String domain = domainsReader.get();
            int ordinal = ordinalsReader.get();
            int htmlFeatures = htmlFeaturesReader.get();
            long documentMetadata = domainMetadataReader.get();
            int length = lengthsReader.get();

            List<String> words = keywordsReader.get();
            List<GammaCodedSequence> positions = termPositionsReader.get();
            byte[] metas = termMetaReader.get();
            byte[] spanCodes = spanCodesReader.get();
            List<GammaCodedSequence> spans = spansReader.get();

            return new KeywordsProjection(
                    domain,
                    ordinal,
                    htmlFeatures,
                    documentMetadata,
                    length,
                    words,
                    metas,
                    positions,
                    spanCodes,
                    spans
            );
        }

    }

    public static class MetadataReader extends SlopTable {
        private final StringColumnReader domainsReader;
        private final StringColumnReader urlsReader;
        private final VarintColumnReader ordinalsReader;
        private final StringColumnReader titlesReader;
        private final StringColumnReader descriptionsReader;

        private final IntColumnReader htmlFeaturesReader;
        private final StringColumnReader htmlStandardsReader;
        private final IntColumnReader lengthsReader;
        private final LongColumnReader hashesReader;
        private final FloatColumnReader qualitiesReader;
        private final IntColumnReader pubYearReader;

        public MetadataReader(SlopPageRef<SlopDocumentRecord> pageRef) throws IOException{
            this(pageRef.baseDir(), pageRef.page());
        }

        public MetadataReader(Path baseDir, int page) throws IOException {
            super(page);

            this.domainsReader = domainsColumn.open(this, baseDir);
            this.urlsReader = urlsColumn.open(this, baseDir);
            this.ordinalsReader = ordinalsColumn.open(this, baseDir);
            this.titlesReader = titlesColumn.open(this, baseDir);
            this.descriptionsReader = descriptionsColumn.open(this, baseDir);
            this.htmlFeaturesReader = htmlFeaturesColumn.open(this, baseDir);
            this.htmlStandardsReader = htmlStandardsColumn.open(this, baseDir);
            this.lengthsReader = lengthsColumn.open(this, baseDir);
            this.hashesReader = hashesColumn.open(this, baseDir);
            this.qualitiesReader = qualitiesColumn.open(this, baseDir);
            this.pubYearReader = pubYearColumn.open(this, baseDir);
        }

        public boolean hasMore() throws IOException {
            return domainsReader.hasRemaining();
        }

        public MetadataProjection next() throws IOException {
            int pubYear = pubYearReader.get();
            return new MetadataProjection(
                    domainsReader.get(),
                    urlsReader.get(),
                    ordinalsReader.get(),
                    titlesReader.get(),
                    descriptionsReader.get(),
                    htmlFeaturesReader.get(),
                    htmlStandardsReader.get(),
                    lengthsReader.get(),
                    hashesReader.get(),
                    qualitiesReader.get(),
                    pubYear < 0 ? null : pubYear
            );
        }

    }

    public static class Writer extends SlopTable {
        private final StringColumnWriter domainsWriter;
        private final StringColumnWriter urlsWriter;
        private final VarintColumnWriter ordinalsWriter;
        private final StringColumnWriter statesWriter;
        private final StringColumnWriter stateReasonsWriter;
        private final StringColumnWriter titlesWriter;
        private final StringColumnWriter descriptionsWriter;
        private final IntColumnWriter htmlFeaturesWriter;
        private final StringColumnWriter htmlStandardsWriter;
        private final IntColumnWriter lengthsWriter;
        private final LongColumnWriter hashesWriter;
        private final FloatColumnWriter qualitiesWriter;
        private final LongColumnWriter domainMetadataWriter;
        private final IntColumnWriter pubYearWriter;
        private final ObjectArrayColumnWriter<String> keywordsWriter;
        private final ByteArrayColumnWriter termMetaWriter;
        private final GammaCodedSequenceArrayWriter termPositionsWriter;
        private final ByteArrayColumnWriter spansCodesWriter;
        private final GammaCodedSequenceArrayWriter spansWriter;

        public Writer(Path baseDir, int page) throws IOException {
            super(page);

            domainsWriter = domainsColumn.create(this, baseDir);
            urlsWriter = urlsColumn.create(this, baseDir);
            ordinalsWriter = ordinalsColumn.create(this, baseDir);
            statesWriter = statesColumn.create(this, baseDir);
            stateReasonsWriter = stateReasonsColumn.create(this, baseDir);
            titlesWriter = titlesColumn.create(this, baseDir);
            descriptionsWriter = descriptionsColumn.create(this, baseDir);
            htmlFeaturesWriter = htmlFeaturesColumn.create(this, baseDir);
            htmlStandardsWriter = htmlStandardsColumn.create(this, baseDir);
            lengthsWriter = lengthsColumn.create(this, baseDir);
            hashesWriter = hashesColumn.create(this, baseDir);
            qualitiesWriter = qualitiesColumn.create(this, baseDir);
            domainMetadataWriter = domainMetadata.create(this, baseDir);
            pubYearWriter = pubYearColumn.create(this, baseDir);

            keywordsWriter = keywordsColumn.create(this, baseDir);
            termMetaWriter = termMetaColumn.create(this, baseDir);
            termPositionsWriter = termPositionsColumn.create(this, baseDir);

            spansCodesWriter = spanCodesColumn.create(this, baseDir);
            spansWriter = spansColumn.create(this, baseDir);
        }

        public void write(SlopDocumentRecord record) throws IOException {
            domainsWriter.put(record.domain());
            urlsWriter.put(record.url());
            ordinalsWriter.put(record.ordinal());
            statesWriter.put(record.state());
            stateReasonsWriter.put(record.stateReason());
            titlesWriter.put(record.title());
            descriptionsWriter.put(record.description());
            htmlFeaturesWriter.put(record.htmlFeatures());
            htmlStandardsWriter.put(record.htmlStandard());
            lengthsWriter.put(record.length());
            hashesWriter.put(record.hash());
            qualitiesWriter.put(record.quality());
            domainMetadataWriter.put(record.documentMetadata());

            if (record.pubYear == null) {
                pubYearWriter.put(-1);
            } else {
                pubYearWriter.put(record.pubYear());
            }

            keywordsWriter.put(record.words());
            termMetaWriter.put(record.metas());
            termPositionsWriter.put(record.positions());
            spansCodesWriter.put(record.spanCodes());
            spansWriter.put(record.spans());
        }
    }
}
