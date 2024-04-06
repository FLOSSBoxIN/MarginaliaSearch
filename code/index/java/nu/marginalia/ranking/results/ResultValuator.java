package nu.marginalia.ranking.results;

import nu.marginalia.api.searchquery.model.compiled.CompiledQuery;
import nu.marginalia.api.searchquery.model.results.ResultRankingContext;
import nu.marginalia.api.searchquery.model.results.ResultRankingParameters;
import nu.marginalia.api.searchquery.model.results.SearchResultKeywordScore;
import nu.marginalia.model.crawl.HtmlFeature;
import nu.marginalia.model.crawl.PubDate;
import nu.marginalia.model.idx.DocumentFlags;
import nu.marginalia.model.idx.DocumentMetadata;
import nu.marginalia.ranking.results.factors.*;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class ResultValuator {
    final static double scalingFactor = 500.;

    private final Bm25Factor bm25Factor;
    private final TermCoherenceFactor termCoherenceFactor;

    private static final Logger logger = LoggerFactory.getLogger(ResultValuator.class);

    @Inject
    public ResultValuator(Bm25Factor bm25Factor,
                          TermCoherenceFactor termCoherenceFactor) {
        this.bm25Factor = bm25Factor;
        this.termCoherenceFactor = termCoherenceFactor;
    }

    public double calculateSearchResultValue(CompiledQuery<SearchResultKeywordScore> scores,
                                             int length,
                                             ResultRankingContext ctx)
    {
        if (scores.size() == 0)
            return Double.MAX_VALUE;
        if (length < 0)
            length = 5000;

        long documentMetadata = scores.at(0).encodedDocMetadata();
        int features = scores.at(0).htmlFeatures();
        var rankingParams = ctx.params;

        int rank = DocumentMetadata.decodeRank(documentMetadata);
        int asl = DocumentMetadata.decodeAvgSentenceLength(documentMetadata);
        int quality = DocumentMetadata.decodeQuality(documentMetadata);
        int size = DocumentMetadata.decodeSize(documentMetadata);
        int flagsPenalty = flagsPenalty(features, documentMetadata & 0xFF, size);
        int topology = DocumentMetadata.decodeTopology(documentMetadata);
        int year = DocumentMetadata.decodeYear(documentMetadata);

        double averageSentenceLengthPenalty = (asl >= rankingParams.shortSentenceThreshold ? 0 : -rankingParams.shortSentencePenalty);

        final double qualityPenalty = calculateQualityPenalty(size, quality, rankingParams);
        final double rankingBonus = (255. - rank) * rankingParams.domainRankBonus;
        final double topologyBonus = Math.log(1 + topology);
        final double documentLengthPenalty = length > rankingParams.shortDocumentThreshold ? 0 : -rankingParams.shortDocumentPenalty;
        final double temporalBias;

        if (rankingParams.temporalBias == ResultRankingParameters.TemporalBias.RECENT) {
            temporalBias = - Math.abs(year - PubDate.MAX_YEAR) * rankingParams.temporalBiasWeight;
        } else if (rankingParams.temporalBias == ResultRankingParameters.TemporalBias.OLD) {
            temporalBias = - Math.abs(year - PubDate.MIN_YEAR) * rankingParams.temporalBiasWeight;
        } else {
            temporalBias = 0;
        }

        double overallPart = averageSentenceLengthPenalty
                           + documentLengthPenalty
                           + qualityPenalty
                           + rankingBonus
                           + topologyBonus
                           + temporalBias
                           + flagsPenalty;

        double bestTcf = rankingParams.tcfWeight * termCoherenceFactor.calculate(scores);
        double bestBM25F = rankingParams.bm25FullWeight * bm25Factor.calculateBm25(rankingParams.prioParams, scores, length, ctx);
        double bestBM25P = rankingParams.bm25PrioWeight * bm25Factor.calculateBm25Prio(rankingParams.prioParams, scores, ctx);

        double overallPartPositive = Math.max(0, overallPart);
        double overallPartNegative = -Math.min(0, overallPart);

        // Renormalize to 0...15, where 0 is the best possible score;
        // this is a historical artifact of the original ranking function
        return normalize(1.5 * bestTcf + bestBM25F + bestBM25P + overallPartPositive, overallPartNegative);
    }

    private double calculateQualityPenalty(int size, int quality, ResultRankingParameters rankingParams) {
        if (size < 400) {
            if (quality < 5)
                return 0;
            return -quality * rankingParams.qualityPenalty;
        }
        else {
            return -quality * rankingParams.qualityPenalty * 20;
        }
    }

    private int flagsPenalty(int featureFlags, long docFlags, int size) {

        // Short-circuit for index-service, which does not have the feature flags
        if (featureFlags == 0)
            return 0;

        double penalty = 0;

        boolean isForum = DocumentFlags.GeneratorForum.isPresent(docFlags);
        boolean isWiki = DocumentFlags.GeneratorWiki.isPresent(docFlags);
        boolean isDocs = DocumentFlags.GeneratorDocs.isPresent(docFlags);

        // Penalize large sites harder for any bullshit as it's a strong signal of a low quality site
        double largeSiteFactor = 1.;

        if (!isForum && !isWiki && !isDocs && size > 400) {
            // Long urls-that-look-like-this tend to be poor search results
            if (DocumentMetadata.hasFlags(featureFlags, HtmlFeature.KEBAB_CASE_URL.getFeatureBit()))
                penalty += 30.0;
            else if (DocumentMetadata.hasFlags(featureFlags, HtmlFeature.LONG_URL.getFeatureBit()))
                penalty += 30.;
            else penalty += 5.;

            largeSiteFactor = 2;
        }

        if (DocumentMetadata.hasFlags(featureFlags, HtmlFeature.TRACKING_ADTECH.getFeatureBit()))
            penalty += 7.5 * largeSiteFactor;

        if (DocumentMetadata.hasFlags(featureFlags, HtmlFeature.AFFILIATE_LINK.getFeatureBit()))
            penalty += 5.0 * largeSiteFactor;

        if (DocumentMetadata.hasFlags(featureFlags, HtmlFeature.COOKIES.getFeatureBit()))
            penalty += 2.5 * largeSiteFactor;

        if (DocumentMetadata.hasFlags(featureFlags, HtmlFeature.TRACKING.getFeatureBit()))
            penalty += 2.5 * largeSiteFactor;

        if (isForum || isWiki) {
            penalty = Math.min(0, penalty - 2);
        }

        return (int) -penalty;
    }

    public static double normalize(double value, double penalty) {
        if (value < 0)
            value = 0;

        return Math.sqrt((1.0 + scalingFactor + 10 * penalty) / (1.0 + value));
    }
}
