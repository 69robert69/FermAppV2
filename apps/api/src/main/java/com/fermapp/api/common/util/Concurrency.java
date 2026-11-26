package com.fermapp.api.common.util;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Replacements for the Promise helpers of the TypeScript server, on virtual threads:
 * {@code mapLimit} (bounded parallel work) and {@code Promise.all} for independent loads.
 */
public final class Concurrency {

    private Concurrency() {
    }

    /** Runs the task for every item with at most {@code limit} running at the same time; waits for all. */
    public static <T> void mapLimit(List<T> items, int limit, Consumer<T> task) {
        Semaphore permits = new Semaphore(Math.max(1, limit));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (T item : items) {
                permits.acquireUninterruptibly();
                executor.submit(() -> {
                    try {
                        task.accept(item);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
    }

    /** Runs two independent loads in parallel and returns both results. */
    public static <A, B> Pair<A, B> both(Supplier<A> first, Supplier<B> second) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<A> a = executor.submit(first::get);
            Future<B> b = executor.submit(second::get);
            return new Pair<>(get(a), get(b));
        }
    }

    public record Pair<A, B>(A first, B second) {
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(error.getCause());
        }
    }
}
