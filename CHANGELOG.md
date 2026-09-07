# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.2.0] - 2026-09-07

### Added

- `Retry.executeAsync(Supplier<CompletableFuture<T>>)`: an async counterpart to
  `execute(Callable<T>)` for tasks that are already asynchronous.
  - Applies the exact same retry policy as the sync path: it reuses the
    existing `isRetryable` (exception-type matching via `retryOn`) and
    `computeDelayMillis` (backoff multiplier + jitter) logic rather than
    duplicating it.
  - Never blocks a thread while waiting between attempts: retries are
    scheduled via `CompletableFuture.delayedExecutor(...)` instead of
    `Thread.sleep`.
  - A synchronous throw from the supplier itself (before it returns a
    future) is treated the same as an exceptionally-completed future.
  - Failure causes are unwrapped from `CompletionException` before the
    retryability check, so `retryOn`-configured types match the same way
    they would on the synchronous path.
  - Returns a `CompletableFuture<T>` that completes with the eventual
    result, or completes exceptionally once retries are exhausted or the
    failure isn't retryable.
- README: new "## Async Retry" section with a runnable example, and an
  additional realistic example (a reused policy across multiple calls) in
  "## Usage".
- Tests (`RetryTest`) covering the new async path: eventual success after N
  failures with the exact invocation count asserted, immediate failure on a
  non-retryable exception, exhaustion after `maxAttempts`, success on the
  first try, and a supplier that throws synchronously before returning a
  future.

## [1.1.0] - 2026-09-06

### Added

- Test coverage for documented-but-untested `Retry` edge cases:
  - The constructor rejects `maxAttempts < 1`, a `null` `initialDelay`,
    and a negative `initialDelay`.
  - `withBackoffMultiplier()` rejects a negative multiplier.
  - `withJitter()` rejects a fraction outside `[0, 1]`.
  - Explicitly calling `retryOn()` with zero arguments retries any
    exception, the same as never calling it.
  - `retryOn()` matches a subtype of a configured exception class
    (assignability via `isInstance`), not just an exact type match.
  - `maxAttempts = 1` means no retries at all: the first failure
    propagates immediately and the task is called exactly once.
  - An `Error` (not `Exception`) thrown by the task propagates as the
    same `Error` instance once retries are exhausted, rather than being
    wrapped.

No behavioral changes were needed — all new edge-case tests passed against
the existing implementation.
