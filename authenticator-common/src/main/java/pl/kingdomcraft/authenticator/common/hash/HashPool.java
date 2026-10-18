package pl.kingdomcraft.authenticator.common.hash;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A small, bounded pool for password hashing, apart from every other pool. Hashing is slow and memory-hungry on purpose, so many
 * attempts at once must not be able to use up the proxy: when the queue is full, new work is refused at once ({@link BusyException})
 * instead of piling up.
 */
public final class HashPool implements AutoCloseable {

	/** The pool is saturated; the caller should ask the player to try again in a moment. */
	public static final class BusyException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public BusyException() {
			super("password hashing is busy", null, false, false);
		}
	}

	private final ThreadPoolExecutor executor;

	public HashPool(int threads, int queueSize) {
		final AtomicInteger counter = new AtomicInteger();

		this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize), runnable -> {
			final Thread thread = new Thread(runnable, "Authenticator-Hash-" + counter.incrementAndGet());
			thread.setDaemon(true);

			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	public <T> CompletableFuture<T> submit(Supplier<T> work) {
		final CompletableFuture<T> future = new CompletableFuture<>();

		try {
			this.executor.execute(() -> {
				try {
					future.complete(work.get());
				} catch (final Throwable ex) {
					future.completeExceptionally(ex);
				}
			});

		} catch (final RejectedExecutionException ex) {
			future.completeExceptionally(new BusyException());
		}

		return future;
	}

	/** Work waiting for a thread. */
	public int queued() {
		return this.executor.getQueue().size();
	}

	@Override
	public void close() {
		this.executor.shutdown();
	}
}
