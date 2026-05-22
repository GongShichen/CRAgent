package com.cragent.util;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import javax.net.ssl.SSLException;

public final class Retry {
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 425, 429, 500, 502, 503, 504);

    private Retry() {
    }

    public static <T> T run(String operation, Callable<T> action) {
        return run(operation, policy(3, 500, 3000), action, null);
    }

    public static <T> T run(String operation, RetryPolicy policy, Callable<T> action) {
        return run(operation, policy, action, null);
    }

    public static <T> T run(String operation, RetryPolicy policy, Callable<T> action, BiConsumer<Integer, Throwable> onRetry) {
        int attempts = Math.max(1, policy.maxAttempts());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(operation + " interrupted", e);
                }
                RuntimeException wrapped = e instanceof RuntimeException runtime ? runtime : new IllegalStateException(operation + " failed", e);
                last = wrapped;
                if (attempt == attempts || !isRetryable(e)) {
                    throw wrapped;
                }
                if (onRetry != null) {
                    onRetry.accept(attempt, e);
                }
                sleep(backoffMillis(attempt, policy));
            }
        }
        throw last == null ? new IllegalStateException(operation + " failed") : last;
    }

    public static RetryPolicy policy(int maxAttempts, int initialBackoffMillis, int maxBackoffMillis) {
        return new RetryPolicy(
                Math.max(1, maxAttempts),
                Math.max(0, initialBackoffMillis),
                Math.max(Math.max(0, initialBackoffMillis), maxBackoffMillis)
        );
    }

    public static boolean retryableStatus(int status) {
        return RETRYABLE_STATUS.contains(status);
    }

    public static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException
                    || current instanceof SSLException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long backoffMillis(int attempt, RetryPolicy policy) {
        long base = policy.initialBackoffMillis();
        if (base <= 0) {
            return 0L;
        }
        long exponential;
        try {
            exponential = Math.multiplyExact(base, 1L << Math.min(20, Math.max(0, attempt - 1)));
        } catch (ArithmeticException e) {
            exponential = Long.MAX_VALUE;
        }
        long capped = Math.min(exponential, policy.maxBackoffMillis());
        long jitter = capped <= 1 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(1, capped / 2), capped + 1);
        return Math.max(0L, jitter);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry sleep interrupted", e);
        }
    }

    public record RetryPolicy(int maxAttempts, int initialBackoffMillis, int maxBackoffMillis) {
    }
}
