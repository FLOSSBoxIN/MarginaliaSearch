package nu.marginalia.loading.loader;

import nu.marginalia.keyword.model.DocumentKeywords;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.crawl.DomainIndexingState;
import nu.marginalia.model.idx.DocumentMetadata;
import nu.marginalia.converting.instruction.Interpreter;
import nu.marginalia.converting.instruction.instructions.DomainLink;
import nu.marginalia.converting.instruction.instructions.LoadProcessedDocument;
import nu.marginalia.converting.instruction.instructions.LoadProcessedDocumentWithError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Loader implements Interpreter, AutoCloseable {
    private final SqlLoadUrls sqlLoadUrls;
    private final SqlLoadDomains sqlLoadDomains;
    private final SqlLoadDomainLinks sqlLoadDomainLinks;
    private final SqlLoadProcessedDomain sqlLoadProcessedDomain;
    private final SqlLoadProcessedDocument sqlLoadProcessedDocument;
    private final SqlLoadDomainMetadata sqlLoadDomainMetadata;

    private final IndexLoadKeywords indexLoadKeywords;

    private static final Logger logger = LoggerFactory.getLogger(Loader.class);

    private final List<LoadProcessedDocument> processedDocumentList;
    private final List<LoadProcessedDocumentWithError> processedDocumentWithErrorList;


    public final LoaderData data;

    public Loader(int sizeHint,
                  SqlLoadUrls sqlLoadUrls,
                  SqlLoadDomains sqlLoadDomains,
                  SqlLoadDomainLinks sqlLoadDomainLinks,
                  SqlLoadProcessedDomain sqlLoadProcessedDomain,
                  SqlLoadProcessedDocument sqlLoadProcessedDocument,
                  SqlLoadDomainMetadata sqlLoadDomainMetadata,
                  IndexLoadKeywords indexLoadKeywords)
    {
        data = new LoaderData(sizeHint);

        this.sqlLoadUrls = sqlLoadUrls;
        this.sqlLoadDomains = sqlLoadDomains;
        this.sqlLoadDomainLinks = sqlLoadDomainLinks;
        this.sqlLoadProcessedDomain = sqlLoadProcessedDomain;
        this.sqlLoadProcessedDocument = sqlLoadProcessedDocument;
        this.sqlLoadDomainMetadata = sqlLoadDomainMetadata;
        this.indexLoadKeywords = indexLoadKeywords;

        processedDocumentList = new ArrayList<>(sizeHint);
        processedDocumentWithErrorList = new ArrayList<>(sizeHint);
    }


    @Override
    public void loadUrl(EdgeUrl[] urls) {
        sqlLoadUrls.load(data, urls);
    }

    @Override
    public void loadDomain(EdgeDomain[] domains) {
        sqlLoadDomains.load(data, domains);
    }

    @Override
    public void loadRssFeed(EdgeUrl[] rssFeed) {
        logger.debug("loadRssFeed({})", rssFeed, null);
    }

    @Override
    public void loadDomainLink(DomainLink[] links) {
        sqlLoadDomainLinks.load(data, links);
    }

    @Override
    public void loadProcessedDomain(EdgeDomain domain, DomainIndexingState state, String ip) {
        sqlLoadProcessedDomain.load(data, domain, state, ip);
    }

    @Override
    public void loadProcessedDocument(LoadProcessedDocument document) {
        processedDocumentList.add(document);

        if (processedDocumentList.size() > 100) {
            sqlLoadProcessedDocument.load(data, processedDocumentList);
            processedDocumentList.clear();
        }
    }

    @Override
    public void loadProcessedDocumentWithError(LoadProcessedDocumentWithError document) {
        processedDocumentWithErrorList.add(document);

        if (processedDocumentWithErrorList.size() > 100) {
            sqlLoadProcessedDocument.loadWithError(data, processedDocumentWithErrorList);
            processedDocumentWithErrorList.clear();
        }
    }

    @Override
    public void loadKeywords(EdgeUrl url, int features, DocumentMetadata metadata, DocumentKeywords words) {
        try {
            indexLoadKeywords.load(data, url, features, metadata, words);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void loadDomainRedirect(DomainLink link) {
        sqlLoadProcessedDomain.loadAlias(data, link);
    }

    @Override
    public void loadDomainMetadata(EdgeDomain domain, int knownUrls, int goodUrls, int visitedUrls) {
        sqlLoadDomainMetadata.load(data, domain, knownUrls, goodUrls, visitedUrls);
    }

    public void close() {
        if (processedDocumentList.size() > 0) {
            sqlLoadProcessedDocument.load(data, processedDocumentList);
        }
        if (processedDocumentWithErrorList.size() > 0) {
            sqlLoadProcessedDocument.loadWithError(data, processedDocumentWithErrorList);
        }
    }

}
