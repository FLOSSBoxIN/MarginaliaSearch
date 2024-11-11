package nu.marginalia.mqapi.converting;

import nu.marginalia.storage.model.FileStorageId;

import java.nio.file.Path;

public class ConvertRequest {
    public final ConvertAction action;
    public final String inputSource;
    public final FileStorageId crawlStorage;
    public final FileStorageId processedDataStorage;
    public final String baseUrl;

    public ConvertRequest(ConvertAction action, String inputSource, FileStorageId crawlStorage, FileStorageId processedDataStorage, String baseUrl) {
        this.action = action;
        this.inputSource = inputSource;
        this.crawlStorage = crawlStorage;
        this.processedDataStorage = processedDataStorage;
        this.baseUrl = baseUrl;
    }

    public Path getInputPath() {
        if (inputSource == null)
            return null;

        return Path.of(inputSource);
    }

    public static ConvertRequest forCrawlData(FileStorageId sourceId, FileStorageId destId) {
        return new ConvertRequest(
                ConvertAction.ConvertCrawlData,
                null,
                sourceId,
                destId,
                null
        );
    }

    public static ConvertRequest forEncyclopedia(Path sourcePath, String baseUrl, FileStorageId destId) {
        return new ConvertRequest(ConvertAction.SideloadEncyclopedia,
                sourcePath.toString(),
                null,
                destId,
                baseUrl);
    }

    public static ConvertRequest forDirtree(Path sourcePath, FileStorageId destId) {
        return new ConvertRequest(ConvertAction.SideloadDirtree,
                sourcePath.toString(),
                null,
                destId,
                null);
    }

    public static ConvertRequest forWarc(Path sourcePath, FileStorageId destId) {
        return new ConvertRequest(ConvertAction.SideloadWarc,
                sourcePath.toString(),
                null,
                destId,
                null);
    }

    public static ConvertRequest forReddit(Path sourcePath, FileStorageId destId) {
        return new ConvertRequest(ConvertAction.SideloadReddit,
                sourcePath.toString(),
                null,
                destId,
                null);
    }

    public static ConvertRequest forStackexchange(Path sourcePath, FileStorageId destId) {
        return new ConvertRequest(ConvertAction.SideloadStackexchange,
                sourcePath.toString(),
                null,
                destId,
                null);
    }
}
