package nu.marginalia.api.searchquery.model.results.debug;


public record ResultRankingOutputs(double averageSentenceLengthPenalty,
                                   double qualityPenalty,
                                   double rankingBonus,
                                   double topologyBonus,
                                   double documentLengthPenalty,
                                   double temporalBias,
                                   double flagsPenalty,
                                   double overallPart,
                                   double bm25,
                                   double tcfAvgDist,
                                   double tcfFirstPosition)
{
}
