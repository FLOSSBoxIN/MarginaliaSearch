package nu.marginalia.domsample.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DomSampleDbTest {
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("test");
    }

    @AfterEach
    void tearDown() throws IOException {
        FileUtils.deleteDirectory(tempDir.toFile());
    }

    @Test
    public void testSetUp() {
        var dbPath = tempDir.resolve("test.db");
        try (var db = new DomSampleDb(dbPath)) {
        }
        catch (Exception e) {
            fail("Failed to set up database: " + e.getMessage());
        }
    }

    @Test
    public void testSyncDomains() {
        var dbPath = tempDir.resolve("test.db");
        try (var db = new DomSampleDb(dbPath)) {

            db.syncDomains(Set.of("example.com", "test.com", "foobar.com"));
            assertEquals(Set.of("example.com", "test.com", "foobar.com"), new HashSet<>(db.getScheduledDomains()));
            db.syncDomains(Set.of("example.com", "test.com"));
            assertEquals(Set.of("example.com", "test.com"), new HashSet<>(db.getScheduledDomains()));
            db.syncDomains(Set.of("foobar.com", "test.com"));
            assertEquals(Set.of("foobar.com", "test.com"), new HashSet<>(db.getScheduledDomains()));
        }
        catch (Exception e) {
            fail("Failed to sync domains: " + e.getMessage());
        }
    }

    @Test
    public void testFetchDomains() {
        var dbPath = tempDir.resolve("test.db");
        try (var db = new DomSampleDb(dbPath)) {

            db.syncDomains(Set.of("example.com", "test.com", "foobar.com"));
            db.flagDomainAsFetched("example.com");
            db.flagDomainAsFetched("test.com");
            db.flagDomainAsFetched("foobar.com");
            assertEquals(List.of("example.com", "test.com", "foobar.com"), db.getScheduledDomains());
            db.flagDomainAsFetched("test.com");
            assertEquals(List.of("example.com", "foobar.com", "test.com"), db.getScheduledDomains());
        }
        catch (Exception e) {
            fail("Failed to sync domains: " + e.getMessage());
        }
    }

    @Test
    public void saveLoadSingle() {
        var dbPath = tempDir.resolve("test.db");
        try (var db = new DomSampleDb(dbPath)) {
            db.saveSampleRaw("example.com", "http://example.com/sample", "sample data", "requests data", true);
            var samples = db.getSamples("example.com");
            assertEquals(1, samples.size());
            var sample = samples.getFirst();
            assertEquals("example.com", sample.domain());
            assertEquals("http://example.com/sample", sample.url());
            assertEquals("sample data", sample.sample());
            assertEquals("requests data", sample.requests());
            assertTrue(sample.acceptedPopover());
        }
        catch (Exception e) {
            fail("Failed to save/load sample: " + e.getMessage());
        }
    }

    @Test
    public void saveLoadTwo() {
        var dbPath = tempDir.resolve("test.db");
        try (var db = new DomSampleDb(dbPath)) {
            db.saveSampleRaw("example.com", "http://example.com/sample", "sample data", "r1", true);
            db.saveSampleRaw("example.com", "http://example.com/sample2", "sample data2", "r2", false);
            var samples = db.getSamples("example.com");
            assertEquals(2, samples.size());

            Map<String, String> samplesByUrl = new HashMap<>();
            for (var sample : samples) {
                samplesByUrl.put(sample.url(), sample.sample());
            }

            assertEquals("sample data", samplesByUrl.get("http://example.com/sample"));
            assertEquals("sample data2", samplesByUrl.get("http://example.com/sample2"));
        }
        catch (Exception e) {
            fail("Failed to save/load sample: " + e.getMessage());
        }
    }
}