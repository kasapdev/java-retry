package dev.kasapdev.retry;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public final class RetryTest {

    /** Exception type configured as retryable in tests that need one. */
    static final class RetryableException extends Exception {
        RetryableException(String message) {
            super(message);
        }
    }

    /** Exception type deliberately NOT included in retryOn(...) for tests. */
    static final class NonRetryableException extends Exception {
        NonRetryableException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws Exception {
        testSucceedsAfterNFailures();
        testNonRetryableExceptionPropagatesImmediately();
        testExhaustsAllAttemptsThenRethrowsLastFailure();
        testDefaultRetryOnRetriesAnyException();
        testSuccessOnFirstTryCallsTaskOnce();
        testBackoffProducesIncreasingDelays();
        TestKit.finish();
    }

    private static void testSucceedsAfterNFailures() throws Exception {
        final int failuresBeforeSuccess = 3;
        AtomicInteger callCount = new AtomicInteger(0);

        Callable<String> flakyTask = () -> {
            int callNumber = callCount.incrementAndGet();
            if (callNumber <= failuresBeforeSuccess) {
                throw new RetryableException("attempt " + callNumber + " failed");
            }
            return "success-on-call-" + callNumber;
        };

        Retry retry = Retry.of(failuresBeforeSuccess + 2, Duration.ofMillis(2))
                .withBackoffMultiplier(2.0)
                .withJitter(0.0)
                .retryOn(RetryableException.class);

        String result = retry.execute(flakyTask);

        TestKit.check("result is correct after eventual success",
                ("success-on-call-" + (failuresBeforeSuccess + 1)).equals(result));
        TestKit.check("task was called exactly N+1 times (N failures + 1 success)",
                callCount.get() == failuresBeforeSuccess + 1);
    }

    private static void testNonRetryableExceptionPropagatesImmediately() {
        AtomicInteger callCount = new AtomicInteger(0);

        Callable<String> task = () -> {
            callCount.incrementAndGet();
            throw new NonRetryableException("boom");
        };

        Retry retry = Retry.of(5, Duration.ofMillis(2))
                .retryOn(RetryableException.class); // NonRetryableException is not in this list

        Exception caught = null;
        try {
            retry.execute(task);
        } catch (Exception e) {
            caught = e;
        }

        TestKit.check("non-retryable exception is thrown", caught instanceof NonRetryableException);
        TestKit.check("task with non-retryable exception was called exactly once", callCount.get() == 1);
    }

    private static void testExhaustsAllAttemptsThenRethrowsLastFailure() {
        AtomicInteger callCount = new AtomicInteger(0);
        int maxAttempts = 3;

        Callable<String> alwaysFails = () -> {
            int callNumber = callCount.incrementAndGet();
            throw new RetryableException("always fails, call " + callNumber);
        };

        Retry retry = Retry.of(maxAttempts, Duration.ofMillis(1))
                .retryOn(RetryableException.class);

        Exception caught = null;
        try {
            retry.execute(alwaysFails);
        } catch (Exception e) {
            caught = e;
        }

        TestKit.check("exhausted retries rethrows the last failure", caught instanceof RetryableException);
        TestKit.check("exhausted retries called task exactly maxAttempts times", callCount.get() == maxAttempts);
    }

    private static void testDefaultRetryOnRetriesAnyException() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        Callable<Integer> task = () -> {
            int callNumber = callCount.incrementAndGet();
            if (callNumber == 1) {
                throw new RuntimeException("transient failure");
            }
            return 42;
        };

        // No retryOn(...) call at all -> every exception type should be retryable.
        Retry retry = Retry.of(3, Duration.ofMillis(1));

        int result = retry.execute(task);

        TestKit.check("default policy (no retryOn) retries unrecognized exception types", result == 42);
        TestKit.check("default policy called task twice (1 failure + 1 success)", callCount.get() == 2);
    }

    private static void testSuccessOnFirstTryCallsTaskOnce() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        Callable<String> task = () -> {
            callCount.incrementAndGet();
            return "ok";
        };

        Retry retry = Retry.of(5, Duration.ofMillis(1));
        String result = retry.execute(task);

        TestKit.check("immediate success returns correct result", "ok".equals(result));
        TestKit.check("immediate success calls task exactly once (no unnecessary retries)", callCount.get() == 1);
    }

    private static void testBackoffProducesIncreasingDelays() throws Exception {
        // With backoffMultiplier > 1 and no jitter, each retry should wait measurably
        // longer than the previous one. We verify this by timing an all-failing run
        // and checking total elapsed time is at least the sum of the expected delays.
        AtomicInteger callCount = new AtomicInteger(0);
        int maxAttempts = 4;
        long initialDelayMillis = 15;
        double multiplier = 2.0;

        Callable<String> alwaysFails = () -> {
            callCount.incrementAndGet();
            throw new RetryableException("fails");
        };

        Retry retry = Retry.of(maxAttempts, Duration.ofMillis(initialDelayMillis))
                .withBackoffMultiplier(multiplier)
                .withJitter(0.0)
                .retryOn(RetryableException.class);

        long expectedMinMillis = 0;
        for (int i = 0; i < maxAttempts - 1; i++) {
            expectedMinMillis += (long) (initialDelayMillis * Math.pow(multiplier, i));
        }

        long start = System.nanoTime();
        try {
            retry.execute(alwaysFails);
        } catch (Exception ignored) {
            // expected: all attempts fail
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        TestKit.check("exponential backoff waits at least the sum of expected delays",
                elapsedMillis >= expectedMinMillis);
        TestKit.check("backoff test exhausted the configured number of attempts",
                callCount.get() == maxAttempts);
    }
}
