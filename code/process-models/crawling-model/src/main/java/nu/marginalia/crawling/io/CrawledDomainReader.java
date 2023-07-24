package nu.marginalia.crawling.io;

import com.github.luben.zstd.ZstdInputStream;
import com.google.gson.Gson;
import lombok.SneakyThrows;
import nu.marginalia.crawling.model.CrawledDocument;
import nu.marginalia.crawling.model.CrawledDomain;
import nu.marginalia.crawling.model.SerializableCrawlData;
import nu.marginalia.crawling.model.spec.CrawlingSpecification;
import nu.marginalia.model.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

public class CrawledDomainReader {
    private final Gson gson = GsonFactory.get();
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ForkJoinPool pool = new ForkJoinPool(6);

    public CrawledDomainReader() {
    }

    public Iterator<SerializableCrawlData> createIterator(Path fullPath) throws IOException {

        BufferedReader br = new BufferedReader(new InputStreamReader(new ZstdInputStream(new FileInputStream(fullPath.toFile()))));

        return new Iterator<>() {
            SerializableCrawlData next;

            @Override
            @SneakyThrows
            public boolean hasNext() {
                String identifier = br.readLine();
                if (identifier == null) {
                    br.close();
                    return false;
                }
                String data = br.readLine();
                if (data == null) {
                    br.close();
                    return false;
                }

                if (identifier.equals(CrawledDomain.SERIAL_IDENTIFIER)) {
                    next = gson.fromJson(data, CrawledDomain.class);
                } else if (identifier.equals(CrawledDocument.SERIAL_IDENTIFIER)) {
                    next = gson.fromJson(data, CrawledDocument.class);
                }
                else {
                    throw new IllegalStateException("Unknown identifier: " + identifier);
                }
                return true;
            }

            @Override
            public SerializableCrawlData next() {
                return next;
            }
        };
    }

    public Iterator<SerializableCrawlData> createIterator(Path basePath, CrawlingSpecification spec) throws IOException {

        return createIterator(CrawlerOutputFile.getOutputFile(basePath, spec.id, spec.domain));
    }
    
    public CrawledDomain read(Path path) throws IOException {
        DomainDataAssembler domainData = new DomainDataAssembler();

        try (var br = new BufferedReader(new InputStreamReader(new ZstdInputStream(new FileInputStream(path.toFile()))))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("//")) {
                    String identifier = line;
                    String data = br.readLine();

                    pool.execute(() -> deserializeLine(identifier, data, domainData));
                }
            }
        }

        while (!pool.awaitQuiescence(1, TimeUnit.SECONDS));

        return domainData.assemble();
    }


    private void deserializeLine(String identifier, String data, DomainDataAssembler assembler) {
        if (null == data) {
            return;
        }
        if (identifier.equals(CrawledDomain.SERIAL_IDENTIFIER)) {
            assembler.acceptDomain(gson.fromJson(data, CrawledDomain.class));
        } else if (identifier.equals(CrawledDocument.SERIAL_IDENTIFIER)) {
            assembler.acceptDoc(gson.fromJson(data, CrawledDocument.class));
        }
    }

    public Optional<CrawledDomain> readOptionally(Path path) {
        try {
            return Optional.of(read(path));
        }
        catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static class DomainDataAssembler {
        private CrawledDomain domainPrototype;
        private final List<CrawledDocument> docs = new ArrayList<>();

        public synchronized void acceptDomain(CrawledDomain domain) {
            this.domainPrototype = domain;
        }

        public synchronized void acceptDoc(CrawledDocument doc) {
            docs.add(doc);
        }

        public synchronized CrawledDomain assemble() {
            if (!docs.isEmpty()) {
                if (domainPrototype.doc == null)
                    domainPrototype.doc = new ArrayList<>();

                domainPrototype.doc.addAll(docs);
            }
            return domainPrototype;
        }
    }
}
