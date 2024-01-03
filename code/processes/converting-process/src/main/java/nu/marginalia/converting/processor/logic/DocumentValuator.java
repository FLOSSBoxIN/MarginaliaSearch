package nu.marginalia.converting.processor.logic;

import crawlercommons.utils.Strings;
import nu.marginalia.crawling.model.CrawledDocument;
import nu.marginalia.model.html.HtmlStandard;
import nu.marginalia.converting.model.DisqualifiedException;
import nu.marginalia.model.crawl.HtmlFeature;
import org.jetbrains.annotations.NotNull;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeVisitor;

import java.util.List;
import java.util.Set;

public class DocumentValuator {

    public double getQuality(CrawledDocument crawledDocument,
                             HtmlStandard htmlStandard,
                             Document parsedDocument,
                             int textLength) throws DisqualifiedException {

        double scriptPenalty = getScriptPenalty(parsedDocument);
        double chatGptPenalty = getChatGptContentFarmPenalty(parsedDocument);

        int rawLength = crawledDocument.documentBody.length();

        if (textLength == 0) {
            throw new DisqualifiedException(DisqualifiedException.DisqualificationReason.LENGTH);
        }

        return Math.log(textLength / (double) (1+rawLength))*htmlStandard.scale
                + htmlStandard.offset
                - scriptPenalty
                - chatGptPenalty;
    }

    private double getChatGptContentFarmPenalty(Document parsedDocument) {
        // easily 90% of modern AI-authored content farm spam have this exact string in one of the headings

        for (String tagName : List.of("h1", "h2", "h3")) {
            for (var elem : parsedDocument.getElementsByTag(tagName)) {
                if (elem.text().startsWith("Benefits of")) {
                    return 10;
                }
            }
        }

        return 0;
    }


    private int getScriptPenalty(Document parsed) {
        var scriptVisitor = new ScriptVisitor();

        parsed.getElementsByTag("script").traverse(scriptVisitor);
        int value = scriptVisitor.score();

        for (var links : parsed.head().getElementsByTag("link")) {
            if (links.hasAttr("onerror") || links.hasAttr("onload")) {
                value += 1;
            }
        }

        return value;
    }

    public double adjustQuality(double quality, Set<HtmlFeature> features) {
        double adjustment = 0;

        if (features.contains(HtmlFeature.TRACKING_ADTECH)) {
            adjustment -= 2.5;
        }
        if (features.contains(HtmlFeature.TRACKING)) {
            adjustment -= 2.5;
        }
        if (features.contains(HtmlFeature.AFFILIATE_LINK)) {
            adjustment -= 1.5;
        }
        if (features.contains(HtmlFeature.GA_SPAM)) {
            adjustment -= 1;
        }
        if (features.contains(HtmlFeature.COOKIES)) {
            adjustment -= 1;
        }
        if (features.contains(HtmlFeature.KEBAB_CASE_URL)) {
            adjustment -= 2;
        }

        if (features.contains(HtmlFeature.COOKIELAW)) {
            adjustment -= 1;
        }
        if (features.contains(HtmlFeature.PARDOT)) {
            adjustment -= 1;
        }
        if (features.contains(HtmlFeature.QUANTCAST)) {
            adjustment -= 1;
        }

        if (features.contains(HtmlFeature.WEBMENTION)) {
            adjustment += 1;
        }
        if (features.contains(HtmlFeature.INDIEAUTH)) {
            adjustment += 1;
        }

        if (quality + adjustment > 0) {
            return 0;
        }
        if (quality + adjustment < -15) {
            return -15;
        }

        return quality + adjustment;
    }

    public static class ScriptVisitor implements NodeVisitor {
        boolean hasBadScript = false;
        int scriptLength = 0;
        double penalty = 0.;

        public int score() {
            return (int)(penalty + (hasBadScript?1:0) + (scriptLength)/1000.);
        }

        @Override
        public void head(@NotNull Node node, int depth) {
            if (node instanceof Element el) {
                visitTag(el);
            }
        }

        public void visitTag(Element el) {
            String srcAttr = el.attr("src");

            if (srcAttr.contains("wp-content") || srcAttr.contains("wp-includes") || srcAttr.contains("jquery")) {
                penalty += 0.49;
            } else if (!Strings.isBlank(srcAttr)) {
                penalty += 1;
            } else {
                var wt = el.wholeText();
                scriptLength += wt.length();
                penalty += 0.25;

                if (!hasBadScript) {
                    hasBadScript = wt.contains(".createElement(");
                }
            }
        }
    }
}
