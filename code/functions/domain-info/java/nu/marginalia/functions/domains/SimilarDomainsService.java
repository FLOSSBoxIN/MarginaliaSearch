package nu.marginalia.functions.domains;

import com.google.inject.Inject;
import com.zaxxer.hikari.HikariDataSource;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntDoubleHashMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import it.unimi.dsi.fastutil.ints.Int2DoubleArrayMap;
import nu.marginalia.api.domains.RpcSimilarDomain;
import nu.marginalia.api.domains.model.SimilarDomain;
import nu.marginalia.api.linkgraph.AggregateLinkGraphClient;
import nu.marginalia.model.EdgeDomain;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

public class SimilarDomainsService {

    private static final Logger logger = LoggerFactory.getLogger(SimilarDomainsService.class);
    private final HikariDataSource dataSource;
    private final AggregateLinkGraphClient linkGraphClient;

    private volatile TIntIntHashMap domainIdToIdx = new TIntIntHashMap(100_000);
    private volatile int[] domainIdxToId;

    public volatile Int2DoubleArrayMap[] relatedDomains;
    public volatile TIntList[] domainNeighbors = null;
    public volatile RoaringBitmap screenshotDomains = null;
    public volatile RoaringBitmap activeDomains = null;
    public volatile RoaringBitmap indexedDomains = null;
    public volatile TIntDoubleHashMap domainRanks = null;
    public volatile String[] domainNames = null;

    volatile boolean isReady = false;


    @Inject
    public SimilarDomainsService(HikariDataSource dataSource, AggregateLinkGraphClient linkGraphClient) {
        this.dataSource = dataSource;
        this.linkGraphClient = linkGraphClient;

        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();

        service.schedule(this::init, 1, TimeUnit.SECONDS);

        // Update screenshot info every hour
        service.scheduleAtFixedRate(this::updateScreenshotInfo, 1, 1, TimeUnit.HOURS);
    }

    private void init() {

        logger.info("Loading similar domains data... ");
        try (var connection = dataSource.getConnection()) {
            try (var stmt = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

                stmt.setFetchSize(1000);
                ResultSet rs;

                rs = stmt.executeQuery("SELECT ID FROM EC_DOMAIN");
                while (rs.next()) {
                    int id = rs.getInt(1);
                    domainIdToIdx.put(id, domainIdToIdx.size());
                }
                domainIdxToId = new int[domainIdToIdx.size()];
                domainIdToIdx.forEachEntry((id, idx) -> {
                    domainIdxToId[idx] = id;
                    return true;
                });
                domainRanks = new TIntDoubleHashMap(100_000, 0.5f, -1, 0.);
                domainNames = new String[domainIdToIdx.size()];
                domainNeighbors = new TIntList[domainIdToIdx.size()];
                screenshotDomains = new RoaringBitmap();
                activeDomains = new RoaringBitmap();
                indexedDomains = new RoaringBitmap();
                relatedDomains = new Int2DoubleArrayMap[domainIdToIdx.size()];

                logger.info("Loaded {} domain IDs", domainIdToIdx.size());

                rs = stmt.executeQuery("""
                    SELECT DOMAIN_ID, NEIGHBOR_ID, RELATEDNESS FROM EC_DOMAIN_NEIGHBORS_2
                    """);

                while (rs.next()) {
                    int did = rs.getInt(1);
                    int nid = rs.getInt(2);

                    int didx = domainIdToIdx.get(did);
                    int nidx = domainIdToIdx.get(nid);

                    int lowerIndex = Math.min(didx, nidx);
                    int higherIndex = Math.max(didx, nidx);

                    if (relatedDomains[lowerIndex] == null)
                        relatedDomains[lowerIndex] = new Int2DoubleArrayMap(4);

                    double rank = Math.round(100 * rs.getDouble(3));
                    if (rank > 0.1) {
                        relatedDomains[lowerIndex].put(higherIndex, rank);
                    }

                    if (domainNeighbors[didx] == null)
                        domainNeighbors[didx] = new TIntArrayList(4);
                    if (domainNeighbors[nidx] == null)
                        domainNeighbors[nidx] = new TIntArrayList(4);

                    domainNeighbors[didx].add(nidx);
                    domainNeighbors[nidx].add(didx);
                }

                logger.info("Loaded {} related domains", relatedDomains.length);


                rs = stmt.executeQuery("""
                    SELECT EC_DOMAIN.ID,
                           RANK,
                           STATE='ACTIVE' AS ACTIVE,
                           NODE_AFFINITY > 0 AS INDEXED,
                           EC_DOMAIN.DOMAIN_NAME AS DOMAIN_NAME
                    FROM EC_DOMAIN
                    """);

                while (rs.next()) {
                    final int id = rs.getInt("ID");
                    final int idx = domainIdToIdx.get(id);

                    domainRanks.put(idx, Math.round(100 * (1. - rs.getDouble("RANK"))));
                    domainNames[idx] = rs.getString("DOMAIN_NAME");

                    if (rs.getBoolean("INDEXED"))
                        indexedDomains.add(idx);

                    if (rs.getBoolean("ACTIVE"))
                        activeDomains.add(idx);
                }

                updateScreenshotInfo();

                logger.info("Loaded {} domains", domainRanks.size());
                isReady = true;
            }
        }
        catch (SQLException throwables) {
            logger.warn("Failed to get domain neighbors for domain", throwables);
        }
    }

    private void updateScreenshotInfo() {
        try (var connection = dataSource.getConnection()) {
            try (var stmt = connection.createStatement()) {
                var rs = stmt.executeQuery("""
                    SELECT EC_DOMAIN.ID
                    FROM EC_DOMAIN INNER JOIN DATA_DOMAIN_SCREENSHOT AS SCREENSHOT ON EC_DOMAIN.DOMAIN_NAME = SCREENSHOT.DOMAIN_NAME
                    """);

                while (rs.next()) {
                    final int id = rs.getInt(1);
                    final int idx = domainIdToIdx.get(id);

                    screenshotDomains.add(idx);
                }
            }
        }
        catch (SQLException throwables) {
            logger.warn("Failed to update screenshot info", throwables);
        }
    }

    public boolean isReady() {
        return isReady;
    }

    private double getRelatedness(int a, int b) {
        int lowerIndex = Math.min(domainIdToIdx.get(a), domainIdToIdx.get(b));
        int higherIndex = Math.max(domainIdToIdx.get(a), domainIdToIdx.get(b));

        if (relatedDomains[lowerIndex] == null)
            return 0;

        return relatedDomains[lowerIndex].get(higherIndex);
    }


    public List<RpcSimilarDomain> getSimilarDomains(int domainId, int count) {
        int domainIdx = domainIdToIdx.get(domainId);

        if (domainNeighbors.length >= domainIdx) {
            return List.of();
        }

        TIntList allIdsList = domainNeighbors[domainIdx];
        if (allIdsList == null)
            return List.of();

        TIntList allIds = new TIntArrayList(new TIntHashSet(allIdsList));

        TIntSet linkingIdsDtoS = getLinkingIdsDToS(domainIdx);
        TIntSet linkingIdsStoD = getLinkingIdsSToD(domainIdx);

        int[] idsArray = new int[allIds.size()];
        int[] idxArray = new int[idsArray.length];

        for (int i = 0; i < idsArray.length; i++) {
            idxArray[i] = allIds.get(i);
            idsArray[i] = domainIdxToId[allIds.get(i)];
        }

        double[] relatednessArray = new double[idsArray.length];
        for (int i = 0; i < idsArray.length; i++) {
            relatednessArray[i] = getRelatedness(domainId, idsArray[i]);
        }

        int[] resultIds = IntStream.range(0, idsArray.length)
                .boxed()
                .sorted((id1, id2) -> {
                    int diff = Double.compare(relatednessArray[id1], relatednessArray[id2]);
                    if (diff != 0)
                        return -diff;
                    return Integer.compare(idsArray[id1], idsArray[id2]);
                })
                .mapToInt(idx -> idxArray[idx])
                .limit(count)
                .toArray();

        List<RpcSimilarDomain> domains = new ArrayList<>();

        for (int idx : resultIds) {
            int id = domainIdxToId[idx];

            if (domainNames[idx].length() > 32)
                continue;

            var linkType = SimilarDomain.LinkType.find(
                    linkingIdsStoD.contains(idx),
                    linkingIdsDtoS.contains(idx)
            );

            domains.add(RpcSimilarDomain.newBuilder()
                    .setDomainId(id)
                    .setUrl(new EdgeDomain(domainNames[idx]).toRootUrlHttp().toString())
                    .setRelatedness(getRelatedness(domainId, id))
                    .setRank(domainRanks.get(idx))
                    .setIndexed(indexedDomains.contains(idx))
                    .setActive(activeDomains.contains(idx))
                    .setScreenshot(screenshotDomains.contains(idx))
                    .setLinkType(RpcSimilarDomain.LINK_TYPE.valueOf(linkType.name()))
                    .build());

        }

        domains.removeIf(this::shouldRemove);

        return domains;
    }

    private boolean shouldRemove(RpcSimilarDomain domainResult) {
        // Remove domains that have a relatively high likelihood of being dead links
        // or not very interesting
        if (!(domainResult.getIndexed() && domainResult.getActive())
            && domainResult.getRelatedness() <= 50)
        {
            return true;
        }

        // Remove domains that are not very similar if there is no mutual link
        if (domainResult.getLinkType() == RpcSimilarDomain.LINK_TYPE.NONE
         && domainResult.getRelatedness() <= 25)
            return true;

        return false;
    }

    private TIntSet getLinkingIdsDToS(int domainIdx) {
        var items = new TIntHashSet();

        for (int id : linkGraphClient.getLinksFromDomain(domainIdxToId[domainIdx])) {
            items.add(domainIdToIdx.get(id));
        }

        return items;
    }

    private TIntSet getLinkingIdsSToD(int domainIdx) {
        var items = new TIntHashSet();

        for (int id : linkGraphClient.getLinksToDomain(domainIdxToId[domainIdx])) {
            items.add(domainIdToIdx.get(id));
        }

        return items;
    }

    public List<RpcSimilarDomain> getLinkingDomains(int domainId, int count) {
        int domainIdx = domainIdToIdx.get(domainId);

        TIntSet linkingIdsDtoS = getLinkingIdsDToS(domainIdx);
        TIntSet linkingIdsStoD = getLinkingIdsSToD(domainIdx);

        TIntSet allIdx = new TIntHashSet(linkingIdsDtoS.size() + linkingIdsStoD.size());
        allIdx.addAll(linkingIdsDtoS);
        allIdx.addAll(linkingIdsStoD);

        int[] idxArray = allIdx.toArray();
        int[] idsArray = new int[idxArray.length];
        for (int i = 0; i < idsArray.length; i++) {
            idsArray[i] = domainIdxToId[idxArray[i]];
        }

        double[] ranksArray = new double[idsArray.length];
        for (int i = 0; i < idxArray.length; i++) {
            ranksArray[i] = this.domainRanks.get(idxArray[i]);
        }
        double[] relatednessArray = new double[idsArray.length];
        for (int i = 0; i < idsArray.length; i++) {
            relatednessArray[i] = getRelatedness(domainId, idsArray[i]);
        }

        int[] linkinessArray = new int[idxArray.length];
        for (int i = 0; i < idxArray.length; i++) {
            linkinessArray[i] = (linkingIdsDtoS.contains(idxArray[i]) ? 1 : 0) + (linkingIdsStoD.contains(idxArray[i]) ? 1 : 0);
        }

        int[] resultIds = IntStream.range(0, idsArray.length)
                .boxed()
                .sorted((id1, id2) -> {
                    int diff = Double.compare(ranksArray[id1], ranksArray[id2]);
                    if (diff != 0)
                        return -diff;
                    diff = Double.compare(relatednessArray[id1], relatednessArray[id2]);
                    if (diff != 0)
                        return -diff;
                    diff = Integer.compare(linkinessArray[id1], linkinessArray[id2]);
                    if (diff != 0)
                        return -diff;
                    return Integer.compare(idsArray[id1], idsArray[id2]);
                })
                .mapToInt(idx -> idsArray[idx])
                .limit(count)
                .toArray();

        List<RpcSimilarDomain> domains = new ArrayList<>();
        for (int id : resultIds) {
            int idx = domainIdToIdx.get(id);

            if (domainNames[idx].length() > 32)
                continue;

            var linkType = SimilarDomain.LinkType.find(
                    linkingIdsStoD.contains(idx),
                    linkingIdsDtoS.contains(idx)
            );

            domains.add(RpcSimilarDomain.newBuilder()
                            .setDomainId(id)
                            .setUrl(new EdgeDomain(domainNames[idx]).toRootUrlHttp().toString())
                            .setRelatedness(getRelatedness(domainId, id))
                            .setRank(domainRanks.get(idx))
                            .setIndexed(indexedDomains.contains(idx))
                            .setActive(activeDomains.contains(idx))
                            .setScreenshot(screenshotDomains.contains(idx))
                            .setLinkType(RpcSimilarDomain.LINK_TYPE.valueOf(linkType.name()))
                    .build());

        }

        domains.removeIf(this::shouldRemove);

        return domains;
    }

}