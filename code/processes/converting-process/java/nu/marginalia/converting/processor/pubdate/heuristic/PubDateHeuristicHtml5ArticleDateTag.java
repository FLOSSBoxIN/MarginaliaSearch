package nu.marginalia.converting.processor.pubdate.heuristic;

import nu.marginalia.converting.processor.pubdate.PubDateEffortLevel;
import nu.marginalia.converting.processor.pubdate.PubDateHeuristic;
import nu.marginalia.converting.processor.pubdate.PubDateParser;
import nu.marginalia.model.EdgeUrl;
import nu.marginalia.model.crawl.PubDate;
import nu.marginalia.model.html.HtmlStandard;
import org.jsoup.nodes.Document;

import java.util.Optional;

public class PubDateHeuristicHtml5ArticleDateTag implements PubDateHeuristic {

    @Override
    public Optional<PubDate> apply(PubDateEffortLevel effortLevel, String headers, EdgeUrl url, Document document, HtmlStandard htmlStandard) {
        // HTML5
        for (var tag : document.select("time[pubdate=\"pubdate\"]")) {
            var maybeDate = PubDateParser.attemptParseDate(tag.attr("datetime"));
            if (maybeDate.isPresent()) {
                return maybeDate;
            }
        }

        return Optional.empty();
    }

}
