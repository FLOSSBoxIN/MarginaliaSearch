package nu.marginalia.crawl.retreival;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import nu.marginalia.bigstring.BigString;
import nu.marginalia.crawling.model.CrawledDocument;
import nu.marginalia.crawling.model.SerializableCrawlData;
import nu.marginalia.lsh.EasyLSH;

import javax.annotation.Nullable;
import java.util.*;

/** A reference to a domain that has been crawled before. */
public class CrawlDataReference {

    private final Iterator<SerializableCrawlData> data;

    public CrawlDataReference(Iterator<SerializableCrawlData> data) {
        this.data = data;
    }

    public CrawlDataReference() {
        this(Collections.emptyIterator());
    }

    @Nullable
    public CrawledDocument nextDocument() {
        while (data.hasNext()) {
            if (data.next() instanceof CrawledDocument doc) {
                return doc;
            }
        }
        return null;
    }

    public boolean isContentBodySame(CrawledDocument one, CrawledDocument other) {
        assert one.documentBody != null;
        assert other.documentBody != null;

        final long contentHashOne = contentHash(one.documentBody);
        final long contentHashOther = contentHash(other.documentBody);

        return EasyLSH.hammingDistance(contentHashOne, contentHashOther) < 4;
    }

    private long contentHash(BigString documentBody) {
        String content = documentBody.decode();
        EasyLSH hash = new EasyLSH();
        int next = 0;

        boolean isInTag = false;

        // In a naive best-effort fashion, extract the text
        // content of the document and feed it into the LSH
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '<') {
                isInTag = true;
            } else if (c == '>') {
                isInTag = false;
            } else if (!isInTag) {
                next = (next << 8) | (c & 0xff);
                hash.addHashUnordered(hashInt(next));
            }
        }

        return hash.get();
    }

    private final HashFunction hashFunction = Hashing.murmur3_128();
    private int hashInt(int v) {
        return hashFunction.hashInt(v).asInt();
    }

}
