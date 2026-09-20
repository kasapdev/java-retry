package dev.kasapdev.retry;

import java.time.Duration;

/**
 * Observes retries, for logging or metrics. Register one with
 * {@link Retry#onRetry(RetryListener)}.
 */
@FunctionalInterface
public interface RetryListener {

    /**
     * Called right before a retry is scheduled: after a failed attempt that will be
     * retried, and before the backoff delay is waited. It is not called after the
     * final attempt, nor for a failure that is not retryable, since no retry follows.
     *
     * @param attempt the 1-based number of the attempt that just failed
     * @param failure the failure of that attempt
     * @param delay   the backoff delay (after jitter and any max-delay cap) about to be waited
     */
    void onRetry(int attempt, Throwable failure, Duration delay);
}
