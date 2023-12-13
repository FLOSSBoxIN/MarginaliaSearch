package nu.marginalia.crawling.io;

import nu.marginalia.crawling.model.SerializableCrawlData;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/** Closable iterator exceptional over serialized crawl data
 * The data may appear in any order, and the iterator must be closed.
 *
 * @see CrawledDomainReader
 * */
public interface SerializableCrawlDataStream extends AutoCloseable {


    SerializableCrawlData next() throws IOException;

    boolean hasNext() throws IOException;

    @Nullable
    default Path path() { return null; }

    // Dummy iterator over nothing
    static SerializableCrawlDataStream empty() {
        return new SerializableCrawlDataStream() {
            @Override
            public SerializableCrawlData next() throws IOException { throw new IllegalStateException("No more data"); }
            @Override
            public boolean hasNext() throws IOException { return false;}
            public void close() {}
        };
    }

    // for testing
    static SerializableCrawlDataStream fromIterator(Iterator<SerializableCrawlData> iterator) {
        return new SerializableCrawlDataStream() {
            @Override
            public SerializableCrawlData next() { return iterator.next(); }
            @Override
            public boolean hasNext() { return iterator.hasNext(); }
            public void close() {}
        };

    }

}
