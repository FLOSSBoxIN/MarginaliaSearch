package nu.marginalia.domsample.db;

import nu.marginalia.WmsaHome;
import org.jsoup.Jsoup;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DomSampleDb implements AutoCloseable {
    private static final String dbFileName = "dom-sample.db";
    private final Connection connection;

    public DomSampleDb() throws SQLException{
        this(WmsaHome.getDataPath().resolve(dbFileName));
    }

    public DomSampleDb(Path dbPath) throws SQLException {
        String dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();

        connection = DriverManager.getConnection(dbUrl);

        try (var stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS samples (url TEXT PRIMARY KEY, domain TEXT, sample BLOB, requests BLOB, accepted_popover BOOLEAN DEFAULT FALSE)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS domain_index ON samples (domain)");
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS schedule (domain TEXT PRIMARY KEY, last_fetch TIMESTAMP DEFAULT NULL)");
        }
    }

    public record Sample(String url, String domain, String sample, String requests, boolean acceptedPopover) {}

    public List<Sample> getSamples(String domain) throws SQLException {
        List<Sample> samples = new ArrayList<>();

        try (var stmt = connection.prepareStatement("""
                SELECT url, sample, requests, accepted_popover
                FROM samples 
                WHERE domain = ?
                """))
        {
            stmt.setString(1, domain);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                samples.add(
                        new Sample(
                                rs.getString("url"),
                                domain,
                                rs.getString("sample"),
                                rs.getString("requests"),
                                rs.getBoolean("accepted_popover")
                        )
                );
            }
        }
        return samples;
    }

    public void saveSample(String domain, String url, String rawContent) throws SQLException {
        var doc = Jsoup.parse(rawContent);

        var networkRequests = doc.getElementById("marginalia-network-requests");

        boolean acceptedPopover = false;

        StringBuilder requestTsv = new StringBuilder();
        if (networkRequests != null) {

            acceptedPopover = !networkRequests.getElementsByClass("marginalia-agreed-cookies").isEmpty();

            for (var request : networkRequests.getElementsByClass("network-request")) {
                String method = request.attr("data-method");
                String urlAttr = request.attr("data-url");
                String timestamp = request.attr("data-timestamp");

                requestTsv
                        .append(method)
                        .append('\t')
                        .append(timestamp)
                        .append('\t')
                        .append(urlAttr.replace('\n', ' '))
                        .append("\n");
            }

            networkRequests.remove();
        }

        doc.body().removeAttr("id");

        String sample = doc.html();

        saveSampleRaw(domain, url, sample, requestTsv.toString().trim(), acceptedPopover);

    }

    record Request(String url, String method, String timestamp, boolean acceptedPopover) {}

    public void saveSampleRaw(String domain, String url, String sample, String requests, boolean acceptedPopover) throws SQLException {
        try (var stmt = connection.prepareStatement("""
                INSERT OR REPLACE 
                INTO samples (domain, url, sample, requests, accepted_popover) 
                VALUES (?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, domain);
            stmt.setString(2, url);
            stmt.setString(3, sample);
            stmt.setString(4, requests);
            stmt.setBoolean(5, acceptedPopover);
            stmt.executeUpdate();
        }
    }

    public void close() throws SQLException {
        connection.close();
    }
}
