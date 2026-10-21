package pl.kingdomcraft.authenticator.common.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class HashPoolTest {

	@Test
	void runsWorkOnItsOwnThreads() throws Exception {
		try (HashPool pool = new HashPool(2, 4)) {
			final String thread = pool.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

			assertTrue(thread.startsWith("Authenticator-Hash-"), thread);
		}
	}

	@Test
	void refusesNewWorkAtOnceWhenTheQueueIsFull() throws Exception {
		final CountDownLatch release = new CountDownLatch(1);

		try (HashPool pool = new HashPool(1, 2)) {
			final List<CompletableFuture<Integer>> accepted = new ArrayList<>();

			// One running (blocked), two queued: the pool is now full.
			for (int i = 0; i < 3; i++) {
				final int n = i;

				accepted.add(pool.submit(() -> {
					try {
						release.await();
					} catch (final InterruptedException ex) {
						Thread.currentThread().interrupt();
					}

					return n;
				}));
			}

			final CompletableFuture<Integer> refused = pool.submit(() -> 99);

			assertTrue(refused.isCompletedExceptionally(), "work must be refused immediately, not wait");
			assertInstanceOf(HashPool.BusyException.class, assertThrows(ExecutionException.class, refused::get).getCause());

			release.countDown();

			for (int i = 0; i < 3; i++)
				assertEquals(i, accepted.get(i).get(5, TimeUnit.SECONDS));

			assertEquals(7, pool.submit(() -> 7).get(5, TimeUnit.SECONDS), "the pool works again once there is room");
		}
	}

	@Test
	void aFailureInTheWorkReachesTheCaller() {
		try (HashPool pool = new HashPool(1, 1)) {
			final ExecutionException ex = assertThrows(ExecutionException.class, () -> pool.<Integer>submit(() -> {
				throw new IllegalStateException("boom");
			}).get(5, TimeUnit.SECONDS));

			assertEquals("boom", ex.getCause().getMessage());
		}
	}
}
