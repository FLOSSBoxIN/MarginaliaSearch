package nu.marginalia.functions.searchquery.query_parser;

import nu.marginalia.functions.searchquery.query_parser.token.QueryToken;
import nu.marginalia.language.encoding.AsciiFlattener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class QueryTokenizer {
    private static final Pattern noisePattern = Pattern.compile("[,\\s]");

    public List<QueryToken> tokenizeQuery(String rawQuery) {
        List<QueryToken> tokens = new ArrayList<>();

        String query = AsciiFlattener.flattenUnicode(rawQuery);
        query = noisePattern.matcher(query).replaceAll(" ");

        for (int i = 0; i < query.length(); i++) {
            int chr = query.charAt(i);

            if ('(' == chr) {
                tokens.add(new QueryToken.LParen());
            }
            else if (')' == chr) {
                tokens.add(new QueryToken.RParen());
            }
            else if ('"' == chr) {
                int end = query.indexOf('"', i+1);

                if (end == -1) {
                    end = query.length();
                }

                tokens.add(new QueryToken.Quot(query.substring(i + 1, end).toLowerCase()));

                i = end;
            }
            else if ('-' == chr) {
                tokens.add(new QueryToken.Minus());
            }
            else if ('?' == chr) {
                tokens.add(new QueryToken.QMark());
            }
            else if (Character.isSpaceChar(chr)) {
                //
            }
            else {

                int end = i+1;
                for (; end < query.length(); end++) {
                    if (query.charAt(end) == ' ' || query.charAt(end) == ')')
                        break;
                }

                String displayStr = query.substring(i, end);
                String str = displayStr.toLowerCase();

                tokens.add(new QueryToken.LiteralTerm(str, displayStr));

                i = end-1;
            }
        }
        return tokens;
    }


}
