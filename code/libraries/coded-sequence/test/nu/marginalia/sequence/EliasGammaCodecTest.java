package nu.marginalia.sequence;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class EliasGammaCodecTest {

    ByteBuffer work = ByteBuffer.allocate(65536);

    @Test
    public void testCodec() {
        var ret = EliasGammaCodec.encode(work, new int[] { 1, 3, 5, 16, 32, 64 });

        List<Integer> decoded = new ArrayList<>();
        List<Integer> expected = List.of(1, 3, 5, 16, 32, 64);

        var sequence = EliasGammaCodec.decode(ret);
        while (sequence.hasNext()) {
            decoded.add(sequence.nextInt());
        }

        assertEquals(expected, decoded);
    }

    @Test
    public void testCodec2() {
        var ret = EliasGammaCodec.encode(work, new int[] { 1, 256 });

        List<Integer> decoded = new ArrayList<>();
        List<Integer> expected = List.of(1, 256);

        var sequence = EliasGammaCodec.decode(ret);
        while (sequence.hasNext()) {
            decoded.add(sequence.nextInt());
        }


        assertEquals(expected, decoded);
    }

    @Test
    public void fuzzTestCodec() {
        Random r = new Random();
        for (int i = 0; i < 1000; i++) {
            int[] sequence = new int[2];
            sequence[0] = 1;
            sequence[1] = 1 + r.nextInt(1, 512);

            var ret = EliasGammaCodec.encode(work, sequence);

            List<Integer> decoded = new ArrayList<>();
            List<Integer> expected = IntStream.of(sequence).boxed().toList();

            try {
                var codedData = EliasGammaCodec.decode(ret);
                while (codedData.hasNext()) {
                    decoded.add(codedData.nextInt());
                }
            }
            catch (Exception e) {
                fail("Exception thrown for " + Arrays.toString(sequence));
            }

            assertEquals(expected, decoded, "Expected " + expected + " but got " + decoded + " for " + Arrays.toString(sequence));

            System.out.println(Arrays.toString(sequence) + " ok");
        }
    }

}