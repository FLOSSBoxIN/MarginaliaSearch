package nu.marginalia.keyword.model;

import nu.marginalia.model.idx.WordMetadata;
import nu.marginalia.sequence.GammaCodedSequence;

import java.io.Serial;
import java.io.Serializable;

public final class DocumentKeywords implements Serializable {

    @Serial
    private static final long serialVersionUID = 1387282293082091432L;

    public final String[] keywords;
    public final long[] metadata;
    public final GammaCodedSequence[] positions;

    public DocumentKeywords(String[] keywords,
                            long[] metadata,
                            GammaCodedSequence[] positions)
    {
        this.keywords = keywords;
        this.metadata = metadata;
        this.positions = positions;

        assert keywords.length == metadata.length;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append('[');
        var pointer = newPointer();
        while (pointer.advancePointer()) {
            sb.append("\n\t ");

            long metadata = pointer.getMetadata();
            String keyword = pointer.getKeyword();
            sb.append(keyword);

            if (metadata != 0) {
                sb.append("/").append(new WordMetadata(metadata));
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

    /** Return a pointer for traversing this structure */
    public DocumentKeywordsPointer newPointer() {
        return new DocumentKeywordsPointer(this);
    }

}


