
package nu.marginalia.index.construction.full;

import nu.marginalia.array.LongArrayFactory;
import nu.marginalia.btree.model.BTreeHeader;
import nu.marginalia.index.construction.DocIdRewriter;
import nu.marginalia.index.construction.PositionsFileConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static nu.marginalia.index.construction.full.TestJournalFactory.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullPreindexFinalizeTest {
    TestJournalFactory journalFactory;
    Path positionsFile;
    Path countsFile;
    Path wordsIdFile;
    Path docsFile;
    Path tempDir;

    @BeforeEach
    public void setUp() throws IOException  {
        journalFactory = new TestJournalFactory();

        positionsFile = Files.createTempFile("positions", ".dat");
        countsFile = Files.createTempFile("counts", ".dat");
        wordsIdFile = Files.createTempFile("words", ".dat");
        docsFile = Files.createTempFile("docs", ".dat");
        tempDir = Files.createTempDirectory("sort");
    }

    @AfterEach
    public void tearDown() throws IOException {
        journalFactory.clear();

        Files.deleteIfExists(countsFile);
        Files.deleteIfExists(wordsIdFile);
        List<Path> contents = new ArrayList<>();
        Files.list(tempDir).forEach(contents::add);
        for (var tempFile : contents) {
            Files.delete(tempFile);
        }
        Files.delete(tempDir);
    }

    @Test
    public void testFinalizeSimple() throws IOException {
        var reader = journalFactory.createReader(new EntryDataWithWordMeta(100, 101, wm(50, 51)));
        var preindex = FullPreindex.constructPreindex(reader,
                new PositionsFileConstructor(positionsFile),
                DocIdRewriter.identity(), tempDir);


        preindex.finalizeIndex(tempDir.resolve( "docs.dat"), tempDir.resolve("words.dat"));
        preindex.delete();

        Path wordsFile = tempDir.resolve("words.dat");
        Path docsFile =  tempDir.resolve("docs.dat");

        assertTrue(Files.exists(wordsFile));
        assertTrue(Files.exists(docsFile));

        System.out.println(Files.size(wordsFile));
        System.out.println(Files.size(docsFile));

        var docsArray = LongArrayFactory.mmapForReadingConfined(docsFile);
        var wordsArray = LongArrayFactory.mmapForReadingConfined(wordsFile);

        var docsHeader = new BTreeHeader(docsArray, 0);
        var wordsHeader = new BTreeHeader(wordsArray, 0);

        assertEquals(1, docsHeader.numEntries());
        assertEquals(1, wordsHeader.numEntries());

        assertEquals(100, docsArray.get(docsHeader.dataOffsetLongs() + 0));
        assertEquals(50, wordsArray.get(wordsHeader.dataOffsetLongs()));
    }


    @Test
    public void testFinalizeSimple2x2() throws IOException {
        var reader = journalFactory.createReader(
                new EntryDataWithWordMeta(100, 101, wm(50, 51)),
                new EntryDataWithWordMeta(101, 101, wm(51, 52))
                );

        var preindex = FullPreindex.constructPreindex(reader,
                new PositionsFileConstructor(positionsFile),
                DocIdRewriter.identity(), tempDir);

        preindex.finalizeIndex(tempDir.resolve( "docs.dat"), tempDir.resolve("words.dat"));
        preindex.delete();

        Path wordsFile = tempDir.resolve("words.dat");
        Path docsFile =  tempDir.resolve("docs.dat");

        assertTrue(Files.exists(wordsFile));
        assertTrue(Files.exists(docsFile));

        System.out.println(Files.size(wordsFile));
        System.out.println(Files.size(docsFile));

        var docsArray = LongArrayFactory.mmapForReadingConfined(docsFile);
        var wordsArray = LongArrayFactory.mmapForReadingConfined(wordsFile);


        var wordsHeader = new BTreeHeader(wordsArray, 0);

        System.out.println(wordsHeader);

        assertEquals(2, wordsHeader.numEntries());

        long offset1 = wordsArray.get(wordsHeader.dataOffsetLongs() + 1);
        long offset2 = wordsArray.get(wordsHeader.dataOffsetLongs() + 3);

        assertEquals(50, wordsArray.get(wordsHeader.dataOffsetLongs()));
        assertEquals(50, wordsArray.get(wordsHeader.dataOffsetLongs()));

        BTreeHeader docsHeader;

        docsHeader = new BTreeHeader(docsArray, offset1);
        System.out.println(docsHeader);
        assertEquals(1, docsHeader.numEntries());

        assertEquals(100, docsArray.get(docsHeader.dataOffsetLongs() + 0));

        docsHeader = new BTreeHeader(docsArray, offset2);
        System.out.println(docsHeader);
        assertEquals(1, docsHeader.numEntries());

        assertEquals(101, docsArray.get(docsHeader.dataOffsetLongs() + 0));
    }
}