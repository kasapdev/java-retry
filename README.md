# java-retry

[![CI](https://github.com/kasapdev/java-retry/actions/workflows/ci.yml/badge.svg)](https://github.com/kasapdev/java-retry/actions/workflows/ci.yml)

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

## API

### `Retry`

| Method | Description |
| --- | --- |
| `static Retry of(int maxAttempts, Duration initialDelay)` | Creates a policy allowing up to `maxAttempts` total attempts (the first call plus retries), with `initialDelay` before the first retry. |
| `Retry withBackoffMultiplier(double m)` | Multiplies the delay by `m` after each successive retry. Default `1.0` (constant delay). |
| `Retry withJitter(double jitterFraction)` | Randomly adjusts each computed delay by up to `+/- jitterFraction * 100`%. Default `0.0` (no jitter). |
| `Retry retryOn(Class<? extends Throwable>... types)` | Restricts retrying to the given exception types (and subtypes). If never called, all exceptions are retryable. |
| `<T> T execute(Callable<T> task)` | Runs `task`, retrying on retryable failures per this policy, and returns its result on success. Throws the last failure once attempts are exhausted, or immediately if the failure type is not retryable. |

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
