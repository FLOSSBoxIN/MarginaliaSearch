package nu.marginalia.index.client;

import nu.marginalia.api.searchquery.IndexProtobufCodec;
import nu.marginalia.api.searchquery.model.query.SearchQuery;
import nu.marginalia.api.searchquery.model.results.ResultRankingParameters;
import nu.marginalia.index.query.limit.QueryLimits;
import nu.marginalia.index.query.limit.SpecificationLimit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class IndexProtobufCodecTest {
    @Test
    public void testSpecLimit() {
        verifyIsIdentityTransformation(SpecificationLimit.none(), l -> IndexProtobufCodec.convertSpecLimit(IndexProtobufCodec.convertSpecLimit(l)));
        verifyIsIdentityTransformation(SpecificationLimit.equals(1), l -> IndexProtobufCodec.convertSpecLimit(IndexProtobufCodec.convertSpecLimit(l)));
        verifyIsIdentityTransformation(SpecificationLimit.greaterThan(1), l -> IndexProtobufCodec.convertSpecLimit(IndexProtobufCodec.convertSpecLimit(l)));
        verifyIsIdentityTransformation(SpecificationLimit.lessThan(1), l -> IndexProtobufCodec.convertSpecLimit(IndexProtobufCodec.convertSpecLimit(l)));
    }

    @Test
    public void testRankingParameters() {
        verifyIsIdentityTransformation(ResultRankingParameters.sensibleDefaults(),
                p -> IndexProtobufCodec.convertRankingParameterss(IndexProtobufCodec.convertRankingParameterss(p, null)));
    }

    @Test
    public void testQueryLimits() {
        verifyIsIdentityTransformation(new QueryLimits(1,2,3,4),
                l -> IndexProtobufCodec.convertQueryLimits(IndexProtobufCodec.convertQueryLimits(l))
                );
    }
    @Test
    public void testSubqery() {
        verifyIsIdentityTransformation(new SearchQuery(
                "qs",
                List.of("a", "b"),
                List.of("c", "d"),
                List.of("e", "f"),
                List.of("g", "h"),
                List.of(List.of("i", "j"), List.of("k"))
                ),
                s -> IndexProtobufCodec.convertRpcQuery(IndexProtobufCodec.convertRpcQuery(s))
        );
    }
    private <T> void verifyIsIdentityTransformation(T val, Function<T,T> transformation) {
        assertEquals(val, transformation.apply(val), val.toString());
    }
}