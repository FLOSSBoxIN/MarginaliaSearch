package nu.marginalia.converting.sideload;

import com.google.gson.Gson;
import com.google.inject.Inject;
import nu.marginalia.atags.AnchorTextKeywords;
import nu.marginalia.atags.source.AnchorTagsSourceFactory;
import nu.marginalia.converting.sideload.dirtree.DirtreeSideloaderFactory;
import nu.marginalia.converting.sideload.encyclopedia.EncyclopediaMarginaliaNuSideloader;
import nu.marginalia.converting.sideload.stackexchange.StackexchangeSideloader;
import nu.marginalia.converting.sideload.warc.WarcSideloadFactory;
import nu.marginalia.keyword.DocumentKeywordExtractor;
import nu.marginalia.language.sentence.ThreadLocalSentenceExtractorProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

public class SideloadSourceFactory {
    private final Gson gson;
    private final SideloaderProcessing sideloaderProcessing;
    private final ThreadLocalSentenceExtractorProvider sentenceExtractorProvider;
    private final DocumentKeywordExtractor documentKeywordExtractor;
    private final AnchorTextKeywords anchorTextKeywords;
    private final AnchorTagsSourceFactory anchorTagsSourceFactory;
    private final DirtreeSideloaderFactory dirtreeSideloaderFactory;
    private final WarcSideloadFactory warcSideloadFactory;

    @Inject
    public SideloadSourceFactory(Gson gson,
                                 SideloaderProcessing sideloaderProcessing,
                                 ThreadLocalSentenceExtractorProvider sentenceExtractorProvider,
                                 DocumentKeywordExtractor documentKeywordExtractor, AnchorTextKeywords anchorTextKeywords,
                                 AnchorTagsSourceFactory anchorTagsSourceFactory,
                                 DirtreeSideloaderFactory dirtreeSideloaderFactory,
                                 WarcSideloadFactory warcSideloadFactory) {
        this.gson = gson;
        this.sideloaderProcessing = sideloaderProcessing;
        this.sentenceExtractorProvider = sentenceExtractorProvider;
        this.documentKeywordExtractor = documentKeywordExtractor;
        this.anchorTextKeywords = anchorTextKeywords;
        this.anchorTagsSourceFactory = anchorTagsSourceFactory;
        this.dirtreeSideloaderFactory = dirtreeSideloaderFactory;
        this.warcSideloadFactory = warcSideloadFactory;
    }

    public SideloadSource sideloadEncyclopediaMarginaliaNu(Path pathToDbFile, String baseUrl) throws SQLException {
        return new EncyclopediaMarginaliaNuSideloader(pathToDbFile, baseUrl, gson, anchorTagsSourceFactory, anchorTextKeywords, sideloaderProcessing);
    }

    public Collection<? extends SideloadSource> sideloadDirtree(Path pathToYamlFile) throws IOException {
        return dirtreeSideloaderFactory.createSideloaders(pathToYamlFile);
    }

    public Collection<? extends SideloadSource> sideloadWarc(Path pathToWarcFiles) throws IOException {
        return warcSideloadFactory.createSideloaders(pathToWarcFiles);
    }

    public Collection<? extends SideloadSource> sideloadStackexchange(Path pathToDbFileRoot) throws IOException {
        if (Files.isRegularFile(pathToDbFileRoot)) {
            return List.of(new StackexchangeSideloader(pathToDbFileRoot, sentenceExtractorProvider, documentKeywordExtractor));
        }
        else if (Files.isDirectory(pathToDbFileRoot)) {
            try (var dirs = Files.walk(pathToDbFileRoot)) {
                return dirs
                        .filter(Files::isRegularFile)
                        .filter(f -> f.toFile().getName().endsWith(".db"))
                        .map(dbFile -> new StackexchangeSideloader(dbFile, sentenceExtractorProvider, documentKeywordExtractor))
                        .toList();
            }
        }
        else { // unix socket, etc
            throw new IllegalArgumentException("Path to stackexchange db file(s) must be a file or directory");
        }
    }
}
