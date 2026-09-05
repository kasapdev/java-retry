package dev.kasapdev.retry;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A small, fluent retry utility with exponential backoff, optional jitter, and
 * selective retrying based on exception type.
 *
 * <p>Example:
 * <pre>{@code
 * Retry retry = Retry.of(4, Duration.ofMillis(100))
 *         .withBackoffMultiplier(2.0)
 *         .withJitter(0.2)
 *         .retryOn(java.io.IOException.class);
 *
 * String result = retry.execute(() -> callFlakyService());
 * }</pre>
 *
 * <p>{@code maxAttempts} counts the total number of attempts, including the
 * first (non-retry) call. If {@link #retryOn(Class[])} is never called, or is
 * called with an empty list, every {@link Throwable} is considered retryable.
 * If it is called with one or more classes, only exceptions assignable to one
 * of those classes are retried; any other exception propagates immediately
 * without consuming a retry or sleeping.
 *
 * <p>This class is a mutable fluent builder; it is not thread-safe to
 * configure concurrently, but {@link #execute(Callable)} may be called
 * repeatedly (including concurrently) once configuration is complete.
 */
public final class Retry {

    private final int maxAttempts;
    private final Duration initialDelay;
    private double backoffMultiplier = 1.0;
    private double jitterFraction = 0.0;
    private List<Class<? extends Throwable>> retryableExceptions = Collections.emptyList();

    private Retry(int maxAttempts, Duration initialDelay) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be non-null and non-negative");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
    }

    /**
     * Creates a new retry policy allowing up to {@code maxAttempts} total attempts
     * (the first call plus retries), waiting {@code initialDelay} before the first retry.
     */
    public static Retry of(int maxAttempts, Duration initialDelay) {
        return new Retry(maxAttempts, initialDelay);
    }

    /**
     * Sets the multiplier applied to the delay after each successive retry
     * (exponential backoff). A value of {@code 1.0} (the default) means a
     * constant delay between retries.
     */
    public Retry withBackoffMultiplier(double backoffMultiplier) {
        if (backoffMultiplier < 0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 0, got " + backoffMultiplier);
        }
        this.backoffMultiplier = backoffMultiplier;
        return this;
    }

    /**
     * Sets the jitter fraction applied to each computed delay as a random
     * +/- percentage. For example, {@code 0.2} randomly adjusts each delay by
     * up to 20% in either direction. Default is {@code 0.0} (no jitter).
     */
    public Retry withJitter(double jitterFraction) {
        if (jitterFraction < 0 || jitterFraction > 1) {
            throw new IllegalArgumentException("jitterFraction must be between 0 and 1, got " + jitterFraction);
        }
        this.jitterFraction = jitterFraction;
        return this;
    }

    /**
     * Restricts retrying to only the given exception types (and their subtypes).
     * If never called, or called with an empty array, all exceptions are retryable.
     */
    @SafeVarargs
    public final Retry retryOn(Class<? extends Throwable>... retryableExceptions) {
        this.retryableExceptions = Arrays.asList(retryableExceptions);
        return this;
    }

    /**
     * Executes {@code task}, retrying on failure according to this policy.
     *
     * @return the task's result on success
     * @throws Exception the last failure, once retries are exhausted, or immediately
     *                    if the failure's type is not retryable per {@link #retryOn}
     */
    public <T> T execute(Callable<T> task) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return task.call();
            } catch (Throwable failure) {
                attempt++;
                boolean retryable = isRetryable(failure);
                boolean exhausted = attempt >= maxAttempts;
                if (!retryable || exhausted) {
                    rethrow(failure);
                }
                sleepBeforeRetry(attempt - 1);
            }
        }
    }

    private boolean isRetryable(Throwable failure) {
        if (retryableExceptions.isEmpty()) {
            return true;
        }
        for (Class<? extends Throwable> type : retryableExceptions) {
            if (type.isInstance(failure)) {
                return true;
            }
        }
        return false;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        // Any other Throwable subtype (rare, custom) - wrap so the checked signature is honored.
        throw new RuntimeException(failure);
    }

    /**
     * attemptIndex is 0-based: 0 for the delay before the first retry (i.e. after
     * the 1st failed attempt), 1 for the delay before the second retry, and so on.
     */
    private void sleepBeforeRetry(int attemptIndex) throws InterruptedException {
        long delayMillis = computeDelayMillis(attemptIndex);
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
    }

    private long computeDelayMillis(int attemptIndex) {
        double base = initialDelay.toMillis() * Math.pow(backoffMultiplier, attemptIndex);
        if (jitterFraction > 0) {
            double randomSign = ThreadLocalRandom.current().nextDouble(-jitterFraction, jitterFraction);
            base = base * (1.0 + randomSign);
        }
        return Math.max(0L, Math.round(base));
    }
}
