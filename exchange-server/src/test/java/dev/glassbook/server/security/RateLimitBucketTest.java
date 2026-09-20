package dev.glassbook.server.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitBucketTest {

    @Test
    void burstIsAllowedUpToCapacityThenRejected() {
        RateLimitInterceptor.Bucket bucket = new RateLimitInterceptor.Bucket(3, 60);   // 1 token per second
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }

    @Test
    void tokensComeBackOverTime() throws InterruptedException {
        RateLimitInterceptor.Bucket bucket = new RateLimitInterceptor.Bucket(1, 6_000);   // 100 tokens per second
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
        Thread.sleep(50);
        assertThat(bucket.tryConsume()).isTrue();
    }
}
