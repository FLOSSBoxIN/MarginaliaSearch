package nu.marginalia.crawl.retreival.fetcher;

import nu.marginalia.crawl.retreival.fetcher.socket.IpInterceptingNetworkInterceptor;
import nu.marginalia.crawl.retreival.fetcher.warc.WarcRecordingFetcherClient;
import nu.marginalia.model.EdgeDomain;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRequest;
import org.netpreserve.jwarc.WarcResponse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WarcRecordingFetcherClientTest {
    Path fileName;
    WarcRecordingFetcherClient client;
    OkHttpClient httpClient;
    @BeforeEach
    public void setUp() throws Exception {
        httpClient = new OkHttpClient.Builder()
                .addNetworkInterceptor(new IpInterceptingNetworkInterceptor())
                .build();

        fileName = Files.createTempFile("test", ".warc.gz");
        client = new WarcRecordingFetcherClient(fileName, new EdgeDomain("www.marginalia.nu"));
    }

    @AfterEach
    public void tearDown() throws Exception {
        client.close();
        Files.delete(fileName);
    }

    @Test
    void fetch() throws NoSuchAlgorithmException, IOException, URISyntaxException, InterruptedException {
        client.fetch(httpClient, new Request.Builder().url("https://www.marginalia.nu/")
                .addHeader("User-agent", "test.marginalia.nu")
                .addHeader("Accept-Encoding", "gzip")
                .get().build());

        new GZIPInputStream(Files.newInputStream(fileName)).transferTo(System.out);

        Map<String, String> sampleData = new HashMap<>();
        try (var warcReader = new WarcReader(fileName)) {
            warcReader.forEach(record -> {
                if (record instanceof WarcRequest req) {
                    sampleData.put(record.type(), req.target());
                }
                if (record instanceof WarcResponse rsp) {
                    sampleData.put(record.type(), rsp.target());
                }
            });
        }

        assertEquals("https://www.marginalia.nu/", sampleData.get("request"));
        assertEquals("https://www.marginalia.nu/", sampleData.get("response"));
    }
}