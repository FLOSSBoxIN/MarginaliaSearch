package nu.marginalia.functions.searchquery.query_parser.model;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Graph structure for constructing query variants.  The graph should be a directed acyclic graph,
 * with a single start node and a single end node, denoted by QWord.beg() and QWord.end() respectively.
 * <p></p>
 * Naively, every path from the start to the end node should represent a valid query variant, although in
 * practice it is desirable to be clever about how to evaluate the paths, to avoid combinatorial explosion.
 */
public class QWordGraph implements Iterable<QWord> {


    public record QWordGraphLink(QWord from, QWord to) {}

    private final List<QWordGraphLink> links = new ArrayList<>();
    private final Map<QWord, List<QWord>> fromTo = new HashMap<>();
    private final Map<QWord, List<QWord>> toFrom = new HashMap<>();

    private int wordId = 0;

    public QWordGraph(String... words) {
        this(List.of(words));
    }

    public QWordGraph(List<String> words) {
        QWord beg = QWord.beg();
        QWord end = QWord.end();

        var prev = beg;

        for (String s : words) {
            var word = new QWord(wordId++, s);
            addLink(prev, word);
            prev = word;
        }

        addLink(prev, end);
    }

    public void addVariant(QWord original, String word) {
        var siblings = getVariants(original);
        if (siblings.stream().anyMatch(w -> w.word().equals(word)))
            return;

        var newWord = new QWord(wordId++, original, word);

        for (var prev : getPrevOriginal(original))
            addLink(prev, newWord);
        for (var next : getNextOriginal(original))
            addLink(newWord, next);
    }

    public void addVariantForSpan(QWord first, QWord last, String word) {
        var newWord = new QWord(wordId++, first, word);

        for (var prev : getPrev(first))
            addLink(prev, newWord);
        for (var next : getNext(last))
            addLink(newWord, next);
    }

    public List<QWord> getVariants(QWord original) {
        var prevNext = getPrev(original).stream()
                .flatMap(prev -> getNext(prev).stream())
                .collect(Collectors.toSet());

        return getNext(original).stream()
                .flatMap(next -> getPrev(next).stream())
                .filter(prevNext::contains)
                .collect(Collectors.toList());
    }


    public void addLink(QWord from, QWord to) {
        links.add(new QWordGraphLink(from, to));
        fromTo.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        toFrom.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
    }

    public List<QWordGraphLink> links() {
        return Collections.unmodifiableList(links);
    }
    public List<QWord> nodes() {
        return links.stream()
                .flatMap(l -> Stream.of(l.from(), l.to()))
                .sorted(Comparator.comparing(QWord::ord))
                .distinct()
                .collect(Collectors.toList());
    }

    public QWord node(String word) {
        return nodes().stream()
                .filter(n -> n.word().equals(word))
                .findFirst()
                .orElseThrow();
    }

    public List<QWord> getNext(QWord word) {
        return fromTo.getOrDefault(word, List.of());
    }
    public List<QWord> getNextOriginal(QWord word) {
        return fromTo.getOrDefault(word, List.of())
                .stream()
                .filter(QWord::isOriginal)
                .toList();
    }

    public List<QWord> getPrev(QWord word) {
        return toFrom.getOrDefault(word, List.of());
    }
    public List<QWord> getPrevOriginal(QWord word) {
        return toFrom.getOrDefault(word, List.of())
                .stream()
                .filter(QWord::isOriginal)
                .toList();
    }

    // Returns true if removing the word would disconnect the graph
    // so that there is no path from 'begin' to 'end'.  This is useful
    // in breaking up the graph into smaller component subgraphs, and
    // understanding which vertexes can be re-ordered without changing
    // the semantics of the encoded query.
    public boolean isBypassed(QWord word, QWord begin, QWord end) {
        Set<QWord> edge = new HashSet<>();
        Set<QWord> visited = new HashSet<>();

        edge.add(begin);

        while (!edge.isEmpty()) {
            Set<QWord> next = new HashSet<>();

            for (var w : edge) {
                // Skip the word we're trying find a bypassing route for
                if (w.ord() == word.ord())
                    continue;

                if (Objects.equals(w, end))
                    return true;

                next.addAll(getNext(w));
            }

            next.removeAll(visited);
            visited.addAll(next);
            edge = next;
        }

        return false;
    }

    public Map<QWord, Set<QWord>> forwardReachability() {
        Map<QWord, Set<QWord>> ret = new HashMap<>();

        Set<QWord> edge = Set.of(QWord.beg());
        Set<QWord> visited = new HashSet<>();

        while (!edge.isEmpty()) {
            Set<QWord> next = new LinkedHashSet<>();

            for (var w : edge) {

                for (var n : getNext(w)) {
                    var set = ret.computeIfAbsent(n, k -> new HashSet<>());

                    set.add(w);
                    set.addAll(ret.getOrDefault(w, Set.of()));

                    next.add(n);
                }
            }

            next.removeAll(visited);
            visited.addAll(next);
            edge = next;
        }

        return ret;
    }

    public Map<QWord, Set<QWord>> reverseReachability() {
        Map<QWord, Set<QWord>> ret = new HashMap<>();

        Set<QWord> edge = Set.of(QWord.end());
        Set<QWord> visited = new HashSet<>();

        while (!edge.isEmpty()) {
            Set<QWord> prev = new LinkedHashSet<>();

            for (var w : edge) {

                for (var p : getPrev(w)) {
                    var set = ret.computeIfAbsent(p, k -> new HashSet<>());

                    set.add(w);
                    set.addAll(ret.getOrDefault(w, Set.of()));

                    prev.add(p);
                }
            }

            prev.removeAll(visited);
            visited.addAll(prev);
            edge = prev;
        }

        return ret;
    }

    public record ReachabilityData(List<QWord> sortedNodes,
                            Map<QWord, Integer> sortOrder,

                            Map<QWord, Set<QWord>> forward,
                            Map<QWord, Set<QWord>> reverse)
    {
        public Set<QWord> forward(QWord node) {
            return forward.getOrDefault(node, Set.of());
        }
        public Set<QWord> reverse(QWord node) {
            return reverse.getOrDefault(node, Set.of());
        }

        public Comparator<QWord> topologicalComparator() {
            return Comparator.comparing(sortOrder::get);
        }

    }

    /** Gather data about graph reachability, including the topological order of nodes */
    public ReachabilityData reachability() {
        var forwardReachability = forwardReachability();
        var reverseReachability = reverseReachability();

        List<QWord> nodes = new ArrayList<>(nodes());
        nodes.sort(new SetMembershipComparator<>(forwardReachability));

        Map<QWord, Integer> topologicalOrder = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            topologicalOrder.put(nodes.get(i), i);
        }

        return new ReachabilityData(nodes, topologicalOrder, forwardReachability, reverseReachability);
    }

    static class SetMembershipComparator<T> implements Comparator<T> {
        private final Map<T, Set<T>> membership;

        SetMembershipComparator(Map<T, Set<T>> membership) {
            this.membership = membership;
        }

        @Override
        public int compare(T o1, T o2) {
            return Boolean.compare(isIn(o1, o2), isIn(o2, o1));
        }

        private boolean isIn(T a, T b) {
            return membership.getOrDefault(a, Set.of()).contains(b);
        }
    }

    public String compileToQuery() {
        var wp = new WordPaths(QWord.beg(), QWord.end());
        return wp.render(reachability());
    }


    class WordPaths {
        private final Set<WordPath> paths;

        public final QWord begin;
        public final QWord end;

        public WordPaths(Collection<WordPath> paths) {
            this.paths = Collections.unmodifiableSet(new HashSet<>(paths));

            begin = null;
            end = null;
        }

        public WordPaths(QWord begin, QWord end) {
            this.begin = begin;
            this.end = end;

            this.paths = Collections.unmodifiableSet(listPaths());
        }

        public String render(ReachabilityData reachability) {
            if (paths.size() == 1) {
                return paths.iterator().next().stream().map(QWord::word).collect(Collectors.joining(" "));
            }

            Map<QWord, Integer> commonality = paths.stream().flatMap(WordPath::stream)
                    .collect(Collectors.groupingBy(w -> w, Collectors.summingInt(w -> 1)));

            Set<QWord> commonToAll = new HashSet<>();
            Set<QWord> notCommonToAll = new HashSet<>();

            commonality.forEach((k, v) -> {
                if (v == paths.size()) {
                    commonToAll.add(k);
                }
                else {
                    notCommonToAll.add(k);
                }
            });

            StringJoiner concat = new StringJoiner(" ");
            if (!commonToAll.isEmpty()) { // Case where one or more words are common to all paths

                commonToAll.stream()
                        .sorted(reachability.topologicalComparator())
                        .map(QWord::word)
                        .forEach(concat::add);

                // Deal portion of the paths that do not all share a common word
                if (!notCommonToAll.isEmpty()) {

                    List<WordPath> nonOverlappingPortions = new ArrayList<>();

                    for (var path : paths) {
                        // Project the path onto the divergent nodes (i.e. remove common nodes)
                        var np = path.project(notCommonToAll);
                        if (np.isEmpty())
                            continue;
                        nonOverlappingPortions.add(np);
                    }

                    if (nonOverlappingPortions.size() > 1) {
                        var wp = new WordPaths(nonOverlappingPortions);
                        concat.add(wp.render(reachability));
                    }
                    else if (!nonOverlappingPortions.isEmpty()) {
                        var wp = new WordPaths(nonOverlappingPortions);
                        concat.add(wp.render(reachability));
                    }
                }
            }
            else if (commonality.size() > 1) { // The case where no words are common to all paths

                // Sort the words by commonality, so that we can consider the most common words first
                List<QWord> byCommonality = commonality.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).collect(Collectors.toList()).reversed();

                Map<QWord, List<WordPath>> pathsByCommonWord = new HashMap<>();

                // Mutable copy of the paths
                List<WordPath> allDivergentPaths = new ArrayList<>(paths);

                for (var qw : byCommonality) {
                    if (allDivergentPaths.isEmpty())
                        break;

                    var iter = allDivergentPaths.iterator();
                    while (iter.hasNext()) {
                        var path = iter.next();

                        if (!path.contains(qw)) {
                            continue;
                        }

                        pathsByCommonWord
                                .computeIfAbsent(qw, k -> new ArrayList<>())
                                .add(path.without(qw)); // Remove the common word from the path

                        iter.remove();
                    }
                }

                var branches = pathsByCommonWord.entrySet().stream().map(e -> {
                            String commonWord = e.getKey().word();
                            String branchPart = new WordPaths(e.getValue()).render(reachability);
                            return STR."\{commonWord} \{branchPart}";
                        })
                        .collect(Collectors.joining(" | ", " ( ", " ) "));

                concat.add(branches);

            }

            // Remove any double spaces that may have been introduced
            return concat.toString().replaceAll("\\s+", " ");
        }


        public Set<WordPath> listPaths() {
            assert begin != null;
            assert end != null;

            Set<WordPath> paths = new HashSet<>();
            listPaths(paths, new LinkedList<>(), begin, end);
            return paths;
        }

        private void listPaths(Set<WordPath> acc,
                               LinkedList<QWord> stack,
                               QWord start,
                               QWord end)
        {
            stack.addLast(start);

            if (Objects.equals(start, end)) {
                var nodes = new HashSet<>(stack);

                nodes.remove(this.begin);
                nodes.remove(this.end);

                acc.add(new WordPath(nodes));
            }
            else {
                for (var next : getNext(start)) {
                    listPaths(acc, stack, next, end);
                }
            }

            stack.removeLast();
        }
    }

    public static class WordPath {
        private final Set<QWord> nodes;

        WordPath(Collection<QWord> nodes) {
            this.nodes = new HashSet<>(nodes);
        }

        public boolean contains(QWord node) {
            return nodes.contains(node);
        }

        public WordPath without(QWord word) {
            Set<QWord> newNodes = new HashSet<>(nodes);
            newNodes.remove(word);
            return new WordPath(newNodes);
        }

        public Stream<QWord> stream() {
            return nodes.stream();
        }

        public WordPath project(Set<QWord> nodes) {
            return new WordPath(this.nodes.stream().filter(nodes::contains).collect(Collectors.toSet()));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            WordPath wordPath = (WordPath) o;

            return nodes.equals(wordPath.nodes);
        }

        public boolean isEmpty() {
            return nodes.isEmpty();
        }

        public int size() {
            return nodes.size();
        }

        @Override
        public int hashCode() {
            return nodes.hashCode();
        }

        @Override
        public String toString() {
            return STR."WordPath{nodes=\{nodes}\{'}'}";
        }
    }

    @NotNull
    @Override
    public Iterator<QWord> iterator() {
        return new Iterator<>() {
            QWord pos = QWord.beg();

            @Override
            public boolean hasNext() {
                return !pos.isEnd();
            }

            @Override
            public QWord next() {
                pos = getNextOriginal(pos).getFirst();
                return pos;
            }
        };
    }
}
