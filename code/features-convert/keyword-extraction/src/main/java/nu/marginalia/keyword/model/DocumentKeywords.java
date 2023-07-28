package nu.marginalia.keyword.model;


import nu.marginalia.model.idx.WordMetadata;

import java.io.Serializable;
import java.util.Arrays;

public record DocumentKeywords(String[] keywords,
                               long[] metadata)
implements Serializable
{

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append('[');
        for (int i = 0; i < keywords.length; i++) {
            sb.append("\n\t ");
            if (metadata[i] != 0) {
                sb.append(keywords[i]).append("/").append(new WordMetadata(metadata[i]));
            }
            else {
                sb.append(keywords[i]);
            }
        }
        return sb.append("\n]").toString();
    }

    public boolean isEmpty() {
        return keywords.length == 0;
    }

    public int size() {
        return keywords.length;
    }

    public DocumentKeywords subList(int start, int end) {
        return new DocumentKeywords(Arrays.copyOfRange(keywords, start, end), Arrays.copyOfRange(metadata, start, end));
    }
}
