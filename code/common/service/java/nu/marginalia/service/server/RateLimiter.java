package nu.marginalia.service.server;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RateLimiter {

    private final Map<String, Bucket> bucketMap = new ConcurrentHashMap<>();

    private final int capacity;
    private final int refillRate;

    public RateLimiter(int capacity, int refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;

        Thread.ofPlatform()
                .name("rate-limiter-cleaner")
                .start(() -> {
                    while (true) {
                        cleanIdleBuckets();
                        try {
                            TimeUnit.MINUTES.sleep(30);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                });
    }


    public static RateLimiter forExpensiveRequest() {
        return new RateLimiter(5, 10);
    }

    public static RateLimiter custom(int perMinute) {
        return new RateLimiter(perMinute, 60);
    }

    public static RateLimiter forSpamBots() {
        return new RateLimiter(120, 3600);
    }


    public static RateLimiter forLogin() {
        return new RateLimiter(3, 15);
    }

    private void cleanIdleBuckets() {
        bucketMap.clear();
    }

    public boolean isAllowed() {
        return bucketMap.computeIfAbsent("any",
                (ip) -> createBucket()).tryConsume(1);
    }

    private Bucket createBucket() {
        var refill = Refill.greedy(1, Duration.ofSeconds(refillRate));
        var bw = Bandwidth.classic(capacity, refill);
        return Bucket.builder().addLimit(bw).build();
    }
}
