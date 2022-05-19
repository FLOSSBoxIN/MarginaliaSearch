package nu.marginalia.wmsa.edge.search;

import nu.marginalia.wmsa.client.exception.RemoteException;
import nu.marginalia.wmsa.configuration.server.Context;
import nu.marginalia.wmsa.edge.assistant.client.AssistantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Singleton
public class UnitConversion {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Pattern conversionPattern = Pattern.compile("((\\d+|\\s+|[.()\\-^+%*/]|log[^a-z]|log2[^a-z]|sqrt[^a-z]|log10|cos[^a-z]|sin[^a-z]|tan[^a-z]|log2|pi[^a-z]|e[^a-z]|2pi[^a-z])+)\\s*([a-zA-Z][a-zA-Z^.0-9]*\\s?[a-zA-Z^.0-9]*)\\s+in\\s+([a-zA-Z^.0-9]+\\s?[a-zA-Z^.0-9]*)");
    private final Predicate<String> evalPredicate = Pattern.compile("(\\d+|\\s+|[.()\\-^+%*/]|log|log2|sqrt|log10|cos|sin|tan|pi|e|2pi)+").asMatchPredicate();

    private final AssistantClient assistantClient;

    @Inject
    public UnitConversion(AssistantClient assistantClient) {
        this.assistantClient = assistantClient;
    }

    public Optional<String> tryConversion(Context context, String query) {
        var matcher = conversionPattern.matcher(query);
        if (!matcher.matches())
                return Optional.empty();

        String value = matcher.group(1);
        String from = matcher.group(3);
        String to = matcher.group(4);

        logger.info("{} -> '{}' '{}' '{}'", query, value, from, to);

        try {
            return Optional.of(assistantClient.unitConversion(context, value, from, to).blockingFirst());
        }
        catch (RemoteException ex) {
            return Optional.empty();
        }
    }

    public boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        }
        catch (NumberFormatException ex) {
            return false;
        }
    }

    public Optional<String> tryEval(Context context, String query) {
        if (!evalPredicate.test(query)) {
            return Optional.empty();
        }

        var expr = query.toLowerCase().trim();

        if (expr.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }

        logger.info("eval({})", expr);

        try {
            return Optional.of(assistantClient.evalMath(context, expr).blockingFirst());
        }
        catch (RemoteException ex) {
            return Optional.empty();
        }
    }
}
