package nu.marginalia.db;

import com.google.inject.Inject;
import com.zaxxer.hikari.HikariDataSource;
import lombok.With;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class DomainRankingSetsService {
    private static final Logger logger = LoggerFactory.getLogger(DomainRankingSetsService.class);
    private final HikariDataSource dataSource;

    @Inject
    public DomainRankingSetsService(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<DomainRankingSet> get(String name) throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT NAME, DESCRIPTION, ALGORITHM, DEPTH, DEFINITION
                     FROM CONF_DOMAIN_RANKING_SET
                     WHERE NAME = ?
                     """)) {
            stmt.setString(1, name);
            var rs = stmt.executeQuery();

            if (!rs.next()) {
                return Optional.empty();
            }

            return Optional.of(new DomainRankingSet(
                    rs.getString("NAME"),
                    rs.getString("DESCRIPTION"),
                    DomainSetAlgorithm.valueOf(rs.getString("ALGORITHM")),
                    rs.getInt("DEPTH"),
                    rs.getString("DEFINITION")
            ));
        }
        catch (SQLException ex) {
            logger.error("Failed to get domain set", ex);
            return Optional.empty();
        }
    }

    public void upsert(DomainRankingSet domainRankingSet) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                        REPLACE INTO CONF_DOMAIN_RANKING_SET(NAME, DESCRIPTION, ALGORITHM, DEPTH, DEFINITION)
                        VALUES (?, ?, ?, ?, ?)
                        """))
        {
            stmt.setString(1, domainRankingSet.name());
            stmt.setString(2, domainRankingSet.description());
            stmt.setString(3, domainRankingSet.algorithm().name());
            stmt.setInt(4, domainRankingSet.depth());
            stmt.setString(5, domainRankingSet.definition());
            stmt.executeUpdate();
        }
        catch (SQLException ex) {
            logger.error("Failed to update domain set", ex);
        }
    }

    public void delete(DomainRankingSet domainRankingSet) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                        DELETE FROM CONF_DOMAIN_RANKING_SET
                        WHERE NAME = ?
                        """))
        {
            stmt.setString(1, domainRankingSet.name());
            stmt.executeUpdate();
        }
        catch (SQLException ex) {
            logger.error("Failed to delete domain set", ex);
        }
    }

    public List<DomainRankingSet> getAll() {

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                     SELECT NAME, DESCRIPTION, ALGORITHM, DEPTH, DEFINITION
                     FROM CONF_DOMAIN_RANKING_SET
                     """)) {
            var rs = stmt.executeQuery();
            List<DomainRankingSet> ret = new ArrayList<>();

            while (rs.next()) {
                ret.add(
                    new DomainRankingSet(
                        rs.getString("NAME"),
                        rs.getString("DESCRIPTION"),
                        DomainSetAlgorithm.valueOf(rs.getString("ALGORITHM")),
                        rs.getInt("DEPTH"),
                        rs.getString("DEFINITION"))
                );
            }
            return ret;
        }
        catch (SQLException ex) {
            logger.error("Failed to get domain set", ex);
            return List.of();
        }
    }

    public enum DomainSetAlgorithm {
        /** Use link graph, do a pagerank */
        LINKS_PAGERANK,
        /** Use link graph, do a cheirank */
        LINKS_CHEIRANK,
        /** Use adjacency graph, do a pagerank */
        ADJACENCY_PAGERANK,
        /** Use adjacency graph, do a cheirank */
        ADJACENCY_CHEIRANK,
        /** For reserved names.  Use special algorithm, function of name */
        SPECIAL
    };

    /** Defines a domain ranking set, parameters for the ranking algorithms.
     *
     * @param name Key and name of the set
     * @param description Human-readable description
     * @param algorithm Algorithm to use
     * @param depth Depth of the algorithm
     * @param definition Definition of the set, typically a list of domains or globs for domain-names
    * */
    @With
    public record DomainRankingSet(String name,
                                   String description,
                                   DomainSetAlgorithm algorithm,
                                   int depth,
                                   String definition)
    {

        public Path fileName(Path base) {
            return base.resolve(name().toLowerCase() + ".dat");
        }
        public String[] domains() {
            return Arrays.stream(definition().split("\n+"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .filter(s -> !s.startsWith("#"))
                    .toArray(String[]::new);
        }

    }
}
