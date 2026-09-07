# java-retry

[![CI](https://github.com/kasapdev/java-retry/actions/workflows/ci.yml/badge.svg)](https://github.com/kasapdev/java-retry/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE) ![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)

A small, fluent retry utility for Java with exponential backoff, optional jitter, and selective retrying by exception type. Zero dependencies, pure Java 17, no build tool required.

## Build & Run

```bash
cd java-retry

# Compile the library
javac -d out $(find src/main/java -name "*.java")

# Compile the tests against the compiled library
javac -cp out -d out $(find src/test/java -name "*.java")

# Run the test suite
java -cp out dev.kasapdev.retry.RetryTest
```

All test output lines are prefixed `[PASS]` or `[FAIL]`, ending with a summary line and a non-zero exit code if anything failed.

## Usage

```java
import dev.kasapdev.retry.Retry;

import java.io.IOException;
import java.time.Duration;

public class Example {
    public static void main(String[] args) throws Exception {
        Retry retry = Retry.of(5, Duration.ofMillis(200))   // up to 5 total attempts, 200ms initial delay
                .withBackoffMultiplier(2.0)                 // delay doubles after each failed attempt
                .withJitter(0.2)                             // +/- 20% random jitter on each delay
                .retryOn(IOException.class);                 // only retry on IOException; anything else propagates immediately

        String response = retry.execute(() -> callFlakyNetworkService());
        System.out.println(response);
    }

    static String callFlakyNetworkService() throws IOException {
        // ... real work that might throw IOException ...
        return "ok";
    }
}
```

If `retryOn(...)` is never called (or called with no arguments), every exception type is treated as retryable.

A policy can also be reused across multiple calls once it's built:

```java
import dev.kasapdev.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class ReusablePolicyExample {
    // Built once, safe to call execute(...) repeatedly (including concurrently).
    private static final Retry RETRY = Retry.of(3, Duration.ofMillis(50))
            .withBackoffMultiplier(1.5)
            .retryOn(TimeoutException.class);

    public static void main(String[] args) throws Exception {
        String first = RETRY.execute(() -> fetch("/users/1"));
        String second = RETRY.execute(() -> fetch("/users/2"));
        System.out.println(first + " / " + second);
    }

    static String fetch(String path) throws TimeoutException {
        // ... real work that might throw TimeoutException ...
        return "response for " + path;
    }
}
```

## Async Retry

`executeAsync(Supplier<CompletableFuture<T>>)` applies the same backoff, jitter, and
`retryOn` policy as `execute(Callable<T>)`, but for tasks that are already asynchronous.
It never blocks a thread: on failure, the next attempt is scheduled after the computed
delay via `CompletableFuture.delayedExecutor(...)`, instead of sleeping. The call returns
immediately with a `CompletableFuture<T>` that completes with the eventual result, or
completes exceptionally once retries are exhausted or the failure isn't retryable.

```java
import dev.kasapdev.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class AsyncExample {
    public static void main(String[] args) throws Exception {
        Retry retry = Retry.of(5, Duration.ofMillis(200))
                .withBackoffMultiplier(2.0)
                .withJitter(0.2)
                .retryOn(IOException.class);

        CompletableFuture<String> response = retry.executeAsync(() -> callFlakyAsyncService());

        // Block only where you actually need the value, e.g. at the edge of your program;
        // the retry loop itself never blocks a thread while waiting between attempts.
        System.out.println(response.get());
    }

    static CompletableFuture<String> callFlakyAsyncService() {
        // ... real async work whose returned future may complete exceptionally
        // with IOException ...
        return CompletableFuture.completedFuture("ok");
    }
}
```

Behavior mirrors the synchronous path exactly, since `executeAsync` reuses the same
retryability check (`retryOn`) and delay computation (backoff multiplier + jitter) as
`execute`:

- Each attempt calls the supplier to obtain a `CompletableFuture<T>`. A synchronous
  throw from the supplier itself (before it even returns a future) is treated the same
  as an exceptionally-completed future.
- On success, the returned future completes with that value.
- On a retryable failure with attempts remaining, the next attempt is scheduled after
  the same delay `execute` would have slept for — computed via the same
  backoff-multiplier/jitter formula — but using a delayed executor instead of
  `Thread.sleep`.
- On a non-retryable failure, or once `maxAttempts` is reached, the returned future
  completes exceptionally with that failure (unwrapped from any `CompletionException`
  wrapper, so `retryOn`-configured types match as expected).

## API

### `Retry`

| Method | Description |
| --- | --- |
| `static Retry of(int maxAttempts, Duration initialDelay)` | Creates a policy allowing up to `maxAttempts` total attempts (the first call plus retries), with `initialDelay` before the first retry. |
| `Retry withBackoffMultiplier(double m)` | Multiplies the delay by `m` after each successive retry. Default `1.0` (constant delay). |
| `Retry withJitter(double jitterFraction)` | Randomly adjusts each computed delay by up to `+/- jitterFraction * 100`%. Default `0.0` (no jitter). |
| `Retry retryOn(Class<? extends Throwable>... types)` | Restricts retrying to the given exception types (and subtypes). If never called, all exceptions are retryable. |
| `<T> T execute(Callable<T> task)` | Runs `task`, retrying on retryable failures per this policy, and returns its result on success. Throws the last failure once attempts are exhausted, or immediately if the failure type is not retryable. |
| `<T> CompletableFuture<T> executeAsync(Supplier<CompletableFuture<T>> task)` | Async counterpart of `execute`. Invokes `task` to get a `CompletableFuture<T>` per attempt; on a retryable failure, schedules the next attempt after the computed delay without blocking a thread. Returns a future that completes with the result, or completes exceptionally once exhausted or on a non-retryable failure. |

### Delay formula

For the delay before retry number `n` (0-indexed: `n = 0` is the delay before the *first* retry):

```
delay = initialDelay * backoffMultiplier^n
delay = delay * (1 + random(-jitterFraction, +jitterFraction))   // if jitter > 0
```

### Behavior notes

- `maxAttempts` counts the initial call too: `maxAttempts = 3` means at most 1 initial call + 2 retries.
- A non-retryable exception (one not assignable to any class passed to `retryOn`, when that list is non-empty) is rethrown immediately — the task is not retried and no delay is incurred.
- Once attempts are exhausted, the *last* exception thrown by the task is rethrown.

## License

MIT — see [LICENSE](LICENSE).
