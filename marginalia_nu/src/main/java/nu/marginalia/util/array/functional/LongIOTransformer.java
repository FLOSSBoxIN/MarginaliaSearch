package nu.marginalia.util.array.functional;

import java.io.IOException;

public interface LongIOTransformer {
    long transform(long pos, long old) throws IOException;
}
