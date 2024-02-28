package nu.marginalia.array.algo;

import nu.marginalia.array.functional.*;

import java.io.IOException;

public interface IntArrayTransformations extends IntArrayBase {

    default void forEach(long start, long end, LongIntConsumer consumer) {
        for (long i = start; i < end; i++) {
            consumer.accept(i, get(i));
        }
    }

    default void transformEach(long start, long end, IntTransformer transformer) {
        for (long i = start; i < end; i++) {
            set(i, transformer.transform(i, get(i)));
        }
    }

    default void transformEachIO(long start, long end, IntIOTransformer transformer) throws IOException {
        for (long i = start; i < end; i++) {
            set(i, transformer.transform(i, get(i)));
        }
    }

    default int foldIO(int zero, long start, long end, IntBinaryIOOperation operator) throws IOException {
        int accumulator = zero;

        for (long i = start; i < end; i++) {
            accumulator = operator.apply(accumulator, get(i));
        }

        return accumulator;
    }

    default int fold(int zero, long start, long end, IntBinaryOperation operator) {
        int accumulator = zero;

        for (long i = start; i < end; i++) {
            accumulator = operator.apply(accumulator, get(i));
        }

        return accumulator;
    }

}
