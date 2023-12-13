package nu.marginalia.crawling.io;

import org.apache.logging.log4j.util.Strings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CrawlerOutputFile {

    /** Return the Path to a file for the given id and name */
    public static Path getLegacyOutputFile(Path base, String id, String name) {
        if (id.length() < 4) {
            id = Strings.repeat("0", 4 - id.length()) + id;
        }

        String first = id.substring(0, 2);
        String second = id.substring(2, 4);

        Path destDir = base.resolve(first).resolve(second);
        return destDir.resolve(STR."\{id}-\{filesystemSafeName(name)}.zstd");
    }

    /** Return the Path to a file for the given id and name, creating the prerequisite
     * directory structure as necessary. */
    public static Path createLegacyOutputPath(Path base, String id, String name) throws IOException {
        if (id.length() < 4) {
            id = Strings.repeat("0", 4 - id.length()) + id;
        }

        String first = id.substring(0, 2);
        String second = id.substring(2, 4);

        Path destDir = base.resolve(first).resolve(second);
        if (!Files.exists(destDir)) {
            Files.createDirectories(destDir);
        }
        return destDir.resolve(STR."\{id}-\{filesystemSafeName(name)}.zstd");
    }


    private static String filesystemSafeName(String name) {
        StringBuilder nameSaneBuilder = new StringBuilder();

        name.chars()
                .map(Character::toLowerCase)
                .map(c -> (c & ~0x7F) == 0 ? c : 'X')
                .map(c -> (Character.isDigit(c) || Character.isAlphabetic(c) || c == '.') ? c : 'X')
                .limit(128)
                .forEach(c -> nameSaneBuilder.append((char) c));

        return nameSaneBuilder.toString();

    }

    public static Path createWarcPath(Path basePath, String id, String domain, WarcFileVersion version) throws IOException {
        if (id.length() < 4) {
            id = Strings.repeat("0", 4 - id.length()) + id;
        }

        String first = id.substring(0, 2);
        String second = id.substring(2, 4);

        Path destDir = basePath.resolve(first).resolve(second);
        if (!Files.exists(destDir)) {
            Files.createDirectories(destDir);
        }
        return destDir.resolve(STR."\{id}-\{filesystemSafeName(domain)}-\{version.suffix}.warc.gz");
    }

    public static Path getWarcPath(Path basePath, String id, String domain, WarcFileVersion version) {
        if (id.length() < 4) {
            id = Strings.repeat("0", 4 - id.length()) + id;
        }

        String first = id.substring(0, 2);
        String second = id.substring(2, 4);

        Path destDir = basePath.resolve(first).resolve(second);
        return destDir.resolve(STR."\{id}-\{filesystemSafeName(domain)}.warc\{version.suffix}");
    }

    public enum WarcFileVersion {
        LIVE("open"),
        TEMP("tmp"),
        FINAL("final");

        public final String suffix;

        WarcFileVersion(String suffix) {
            this.suffix = suffix;
        }
    }
}
