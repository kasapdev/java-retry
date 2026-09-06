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
        testInvalidConstructorArgsRejected();
        testInvalidBackoffMultiplierRejected();
        testInvalidJitterFractionRejected();
        testExplicitEmptyRetryOnRetriesAnyException();
        testRetryOnMatchesSubtypeOfConfiguredException();
        testMaxAttemptsOneMeansNoRetries();
        testErrorPropagatesUnwrappedOnceExhausted();
        TestKit.finish();
    }

    /** Error type used to verify Errors propagate unwrapped, distinct from Exception handling. */
    static final class BoomError extends Error {
        BoomError(String message) {
            super(message);
        }
    }

    private static void testInvalidConstructorArgsRejected() {
        boolean threwZeroAttempts = false;
        try {
            Retry.of(0, Duration.ofMillis(10));
        } catch (IllegalArgumentException e) {
            threwZeroAttempts = true;
        }
        TestKit.check("maxAttempts of 0 is rejected", threwZeroAttempts);

        boolean threwNegativeAttempts = false;
        try {
            Retry.of(-1, Duration.ofMillis(10));
        } catch (IllegalArgumentException e) {
            threwNegativeAttempts = true;
        }
        TestKit.check("negative maxAttempts is rejected", threwNegativeAttempts);

        boolean threwNullDelay = false;
        try {
            Retry.of(3, null);
        } catch (IllegalArgumentException e) {
            threwNullDelay = true;
        }
        TestKit.check("a null initialDelay is rejected", threwNullDelay);

        boolean threwNegativeDelay = false;
        try {
            Retry.of(3, Duration.ofMillis(-1));
        } catch (IllegalArgumentException e) {
            threwNegativeDelay = true;
        }
        TestKit.check("a negative initialDelay is rejected", threwNegativeDelay);
    }

    private static void testInvalidBackoffMultiplierRejected() {
        boolean threw = false;
        try {
            Retry.of(3, Duration.ofMillis(10)).withBackoffMultiplier(-0.5);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        TestKit.check("a negative backoff multiplier is rejected", threw);
    }

    private static void testInvalidJitterFractionRejected() {
        boolean threwNegative = false;
        try {
            Retry.of(3, Duration.ofMillis(10)).withJitter(-0.1);
        } catch (IllegalArgumentException e) {
            threwNegative = true;
        }
        TestKit.check("a negative jitter fraction is rejected", threwNegative);

        boolean threwTooLarge = false;
        try {
            Retry.of(3, Duration.ofMillis(10)).withJitter(1.5);
        } catch (IllegalArgumentException e) {
            threwTooLarge = true;
        }
        TestKit.check("a jitter fraction greater than 1 is rejected", threwTooLarge);
    }

    private static void testExplicitEmptyRetryOnRetriesAnyException() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        Callable<Integer> task = () -> {
            int callNumber = callCount.incrementAndGet();
            if (callNumber == 1) {
                throw new RuntimeException("transient failure");
            }
            return 7;
        };

        // Explicitly calling retryOn() with zero arguments must behave the same as never
        // calling it at all: every exception type is retryable.
        Retry retry = Retry.of(3, Duration.ofMillis(1)).retryOn();
        int result = retry.execute(task);

        TestKit.check("explicit empty retryOn() still retries any exception", result == 7);
        TestKit.check("explicit empty retryOn() called task twice", callCount.get() == 2);
    }

    private static void testRetryOnMatchesSubtypeOfConfiguredException() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        Callable<String> task = () -> {
            int callNumber = callCount.incrementAndGet();
            if (callNumber == 1) {
                // ParentRetryableException is registered; this throws a distinct subtype of it.
                throw new ChildRetryableException("subtype failure");
            }
            return "recovered";
        };

        Retry retry = Retry.of(3, Duration.ofMillis(1)).retryOn(ParentRetryableException.class);
        String result = retry.execute(task);

        TestKit.check("retryOn() matches a subtype of the configured exception class", "recovered".equals(result));
        TestKit.check("subtype-matching retry called task twice", callCount.get() == 2);
    }

    /** Non-final base exception used to verify retryOn() matches by assignability (isInstance). */
    static class ParentRetryableException extends Exception {
        ParentRetryableException(String message) {
            super(message);
        }
    }

    /** Subtype of ParentRetryableException, thrown to confirm subtype matching in retryOn(). */
    static final class ChildRetryableException extends ParentRetryableException {
        ChildRetryableException(String message) {
            super(message);
        }
    }

    private static void testMaxAttemptsOneMeansNoRetries() {
        AtomicInteger callCount = new AtomicInteger(0);
        Callable<String> alwaysFails = () -> {
            callCount.incrementAndGet();
            throw new RetryableException("fails once, no retries configured");
        };

        Retry retry = Retry.of(1, Duration.ofMillis(50)).retryOn(RetryableException.class);

        Exception caught = null;
        try {
            retry.execute(alwaysFails);
        } catch (Exception e) {
            caught = e;
        }

        TestKit.check("maxAttempts=1 rethrows the first failure", caught instanceof RetryableException);
        TestKit.check("maxAttempts=1 calls the task exactly once, never retrying", callCount.get() == 1);
    }

    private static void testErrorPropagatesUnwrappedOnceExhausted() {
        AtomicInteger callCount = new AtomicInteger(0);
        Callable<String> throwsError = () -> {
            callCount.incrementAndGet();
            throw new BoomError("catastrophic");
        };

        // Default policy (no retryOn) treats every Throwable, including Errors, as retryable;
        // with maxAttempts=1 there are no actual retries/sleeps, keeping this deterministic.
        Retry retry = Retry.of(1, Duration.ofMillis(50));

        Throwable caught = null;
        try {
            retry.execute(throwsError);
        } catch (Throwable t) {
            caught = t;
        }

        TestKit.check("an Error thrown by the task propagates as the same Error type, not wrapped", caught instanceof BoomError);
        TestKit.check("the Error's message is preserved unwrapped", caught != null && "catastrophic".equals(caught.getMessage()));
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
