package nu.marginalia.assistant.domains;

import com.zaxxer.hikari.HikariDataSource;
import nu.marginalia.geoip.GeoIpDictionary;
import nu.marginalia.model.EdgeDomain;
import nu.marginalia.db.DbDomainQueries;
import nu.marginalia.assistant.client.model.DomainInformation;
import nu.marginalia.query.client.QueryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Singleton
public class DomainInformationService {
    private final GeoIpDictionary geoIpDictionary;

    private DbDomainQueries dbDomainQueries;
    private final QueryClient queryClient;
    private HikariDataSource dataSource;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    public DomainInformationService(
            DbDomainQueries dbDomainQueries,
            GeoIpDictionary geoIpDictionary,
            QueryClient queryClient,
            HikariDataSource dataSource) {
        this.dbDomainQueries = dbDomainQueries;
        this.geoIpDictionary = geoIpDictionary;
        this.queryClient = queryClient;
        this.dataSource = dataSource;
    }


    public Optional<DomainInformation> domainInfo(int domainId) {

        Optional<EdgeDomain> domain = dbDomainQueries.getDomain(domainId);
        if (domain.isEmpty()) {
            return Optional.empty();
        }


        var builder = DomainInformation.builder();
        try (var connection = dataSource.getConnection();
             var stmt = connection.createStatement();
        ) {
            boolean inCrawlQueue;
            int outboundLinks = 0;
            int pagesVisited = 0;

            ResultSet rs;

            rs = stmt.executeQuery(STR."""
                    SELECT IP, NODE_AFFINITY, DOMAIN_NAME, STATE, IFNULL(RANK, 1) AS RANK
                           FROM EC_DOMAIN WHERE ID=\{domainId}
                       """);
            if (rs.next()) {
                String ip = rs.getString("IP");

                builder.ip(ip);
                geoIpDictionary.getAsnInfo(ip).ifPresent(asnInfo -> {
                    builder.asn(asnInfo.asn());
                    builder.asnOrg(asnInfo.org());
                    builder.asnCountry(asnInfo.country());
                });
                builder.ipCountry(geoIpDictionary.getCountry(ip));

                builder.nodeAffinity(rs.getInt("NODE_AFFINITY"));
                builder.domain(new EdgeDomain(rs.getString("DOMAIN_NAME")));
                builder.state(rs.getString("STATE"));
                builder.ranking(Math.round(100.0*(1.0-rs.getDouble("RANK"))));
            }
            rs = stmt.executeQuery(STR."""
                    SELECT 1 FROM CRAWL_QUEUE
                    INNER JOIN EC_DOMAIN ON CRAWL_QUEUE.DOMAIN_NAME = EC_DOMAIN.DOMAIN_NAME
                    WHERE EC_DOMAIN.ID=\{domainId}
                       """);
            inCrawlQueue = rs.next();
            builder.inCrawlQueue(inCrawlQueue);

            builder.incomingLinks(queryClient.countLinksToDomain(domainId));
            builder.outboundLinks(queryClient.countLinksFromDomain(domainId));

            rs = stmt.executeQuery(STR."""
                    SELECT KNOWN_URLS, GOOD_URLS, VISITED_URLS FROM DOMAIN_METADATA WHERE ID=\{domainId}
                       """);
            if (rs.next()) {
                pagesVisited = rs.getInt("VISITED_URLS");

                builder.pagesKnown(rs.getInt("KNOWN_URLS"));
                builder.pagesIndexed(rs.getInt("GOOD_URLS"));
                builder.pagesFetched(rs.getInt("VISITED_URLS"));
            }

            builder.suggestForCrawling((pagesVisited == 0 && outboundLinks == 0 && !inCrawlQueue));

            return Optional.of(builder.build());
        }
        catch (SQLException ex) {
            logger.error("SQL error", ex);
            return Optional.empty();
        }
    }

}
