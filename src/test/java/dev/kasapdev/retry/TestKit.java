package dev.kasapdev.retry;

public final class TestKit {
    private static int failures = 0;

    public static void check(String label, boolean condition) {
        System.out.println((condition ? "[PASS] " : "[FAIL] ") + label);
        if (!condition) failures++;
    }

    public static void finish() {
        System.out.println(failures == 0 ? "\nAll tests passed." : "\n" + failures + " test(s) FAILED.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
