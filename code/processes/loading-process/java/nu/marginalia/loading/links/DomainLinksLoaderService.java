package nu.marginalia.loading.links;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.SneakyThrows;
import nu.marginalia.io.processed.DomainLinkRecordParquetFileReader;
import nu.marginalia.linkgraph.io.DomainLinksWriter;
import nu.marginalia.loading.LoaderInputData;
import nu.marginalia.loading.domains.DomainIdRegistry;
import nu.marginalia.model.processed.DomainLinkRecord;
import nu.marginalia.process.control.ProcessHeartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

@Singleton
public class DomainLinksLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(DomainLinksLoaderService.class);

    private final DomainLinksWriter domainLinkDbWriter;

    @Inject
    public DomainLinksLoaderService(DomainLinksWriter domainLinkDbWriter) {
        this.domainLinkDbWriter = domainLinkDbWriter;
    }

    public boolean loadLinks(DomainIdRegistry domainIdRegistry,
                             ProcessHeartbeat heartbeat,
                             LoaderInputData inputData) throws IOException {

        try (var task = heartbeat.createAdHocTaskHeartbeat("LINKS")) {
            var linkFiles = inputData.listDomainLinkFiles();

            int processed = 0;

            for (var file : linkFiles) {
                task.progress("LOAD", processed++, linkFiles.size());

                loadLinksFromFile(domainIdRegistry, file);
            }

            task.progress("LOAD", processed, linkFiles.size());
        }
        catch (IOException e) {
            logger.error("Failed to load links", e);
            throw e;
        }

        logger.info("Finished");
        return true;
    }

    private void loadLinksFromFile(DomainIdRegistry domainIdRegistry, Path file) throws IOException {
        try (var domainStream = DomainLinkRecordParquetFileReader.stream(file);
             var linkLoader = new LinkLoader(domainIdRegistry))
        {
            logger.info("Loading links from {}", file);
            domainStream.forEach(linkLoader::accept);
        }
    }

    class LinkLoader implements AutoCloseable {
        private final DomainIdRegistry domainIdRegistry;

        public LinkLoader(DomainIdRegistry domainIdRegistry) {
            this.domainIdRegistry = domainIdRegistry;
        }

        @SneakyThrows
        void accept(DomainLinkRecord record) {
            domainLinkDbWriter.write(
                    domainIdRegistry.getDomainId(record.source),
                    domainIdRegistry.getDomainId(record.dest)
            );
        }

        @Override
        public void close() {}
    }
}
