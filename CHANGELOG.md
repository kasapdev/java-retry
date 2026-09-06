# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

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
