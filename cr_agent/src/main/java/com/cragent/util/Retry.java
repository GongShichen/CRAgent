package com.cragent.util;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLException;

public final class Retry {
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 409, 425, 429, 500, 502, 503, 504);

    private Retry() {
    }

    public static <T> T run(String operation, Callable<T> action) {
        int attempts = 3;
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
                sleep(backoffMillis(attempt));
            }
        }
        throw last == null ? new IllegalStateException(operation + " failed") : last;
    }

    public static boolean retryableStatus(int status) {
        return RETRYABLE_STATUS.contains(status);
    }

    private static boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof IOException
                    || current instanceof SSLException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static long backoffMillis(int attempt) {
        return switch (attempt) {
            case 1 -> 500L;
            case 2 -> 1500L;
            default -> 3000L;
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry sleep interrupted", e);
        }
    }
}
