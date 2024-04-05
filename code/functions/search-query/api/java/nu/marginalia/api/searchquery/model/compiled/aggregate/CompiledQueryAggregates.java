package nu.marginalia.api.searchquery.model.compiled.aggregate;

import it.unimi.dsi.fastutil.longs.LongSet;
import nu.marginalia.api.searchquery.model.compiled.CompiledQuery;
import nu.marginalia.api.searchquery.model.compiled.CompiledQueryLong;

import java.util.ArrayList;
import java.util.List;
import java.util.function.*;

/** Contains methods for aggregating across a CompiledQuery or CompiledQueryLong */
public class CompiledQueryAggregates {
    /** Compiled query aggregate that for a single boolean that treats or-branches as logical OR,
     * and and-branches as logical AND operations.  Will return true if there exists a path through
     * the query where the provided predicate returns true for each item.
     */
    static public <T> boolean booleanAggregate(CompiledQuery<T> query, Predicate<T> predicate) {
        return query.root.visit(new CqBooleanAggregate(query, predicate));
    }


    /** Compiled query aggregate that for a 64b bitmask that treats or-branches as logical OR,
     * and and-branches as logical AND operations.
     */
    public static <T> long longBitmaskAggregate(CompiledQuery<T> query, ToLongFunction<T> operator) {
        return query.root.visit(new CqLongBitmaskOperator(query, operator));
    }


    /** Apply the operator to each leaf node, then return the highest minimum value found along any path */
    public static <T> int intMaxMinAggregate(CompiledQuery<T> query, ToIntFunction<T> operator) {
        return query.root.visit(new CqIntMaxMinOperator(query, operator));
    }

    /** Apply the operator to each leaf node, and then return the highest sum of values possible
     * through each branch in the compiled query.
     *
     */
    public static <T> double doubleSumAggregate(CompiledQuery<T> query, ToDoubleFunction<T> operator) {
        return query.root.visit(new CqDoubleSumOperator(query, operator));
    }

    /** Enumerate all possible paths through the compiled query */
    public static List<LongSet> queriesAggregate(CompiledQueryLong query) {
        return new ArrayList<>(query.root().visit(new CqQueryPathsOperator(query)));
    }
}
