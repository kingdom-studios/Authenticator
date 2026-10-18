package pl.kingdomcraft.authenticator.common.limit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

/**
 * Counts events per key in fixed windows: the window of a key starts with its first event and ends a fixed time later, whatever happens
 * in between, then the count starts again. Memory is bounded (the least recently used keys go first), so a flood of different keys
 * cannot grow it without limit. Thread-safe.
 */
public final class RateLimiter<K> {

	private final int limit;
	private final Cache<K, AtomicInteger> counts;

	public RateLimiter(int limit, Duration window, Clock clock) {
		this.limit = limit;
		this.counts = Caffeine.newBuilder().maximumSize(100_000).ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).expireAfter(new Expiry<K, AtomicInteger>() {
			@Override
			public long expireAfterCreate(K key, AtomicInteger value, long currentTime) {
				return window.toNanos();
			}

			@Override
			public long expireAfterUpdate(K key, AtomicInteger value, long currentTime, long currentDuration) {
				return currentDuration; // counting is not an update of the entry, and an update must never extend the window anyway
			}

			@Override
			public long expireAfterRead(K key, AtomicInteger value, long currentTime, long currentDuration) {
				return currentDuration;
			}
		}).build();
	}

	/** Counts one more event for the key. @return how many events the key has had in the current window, this one included */
	public int hit(K key) {
		return this.counts.get(key, k -> new AtomicInteger()).incrementAndGet();
	}

	/** Counts one more event and says whether it is still within the limit. Events over the limit keep counting but never extend the window. */
	public boolean tryAcquire(K key) {
		return this.hit(key) <= this.limit;
	}

	public int count(K key) {
		final AtomicInteger count = this.counts.getIfPresent(key);

		return count == null ? 0 : count.get();
	}

	/** Forgets the key, e.g. after a successful login. */
	public void reset(K key) {
		this.counts.invalidate(key);
	}
}
