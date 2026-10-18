package pl.kingdomcraft.authenticator.common.limit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.support.MutableClock;

class RateLimiterTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
	private final RateLimiter<String> limiter = new RateLimiter<>(3, Duration.ofSeconds(10), this.clock);

	@Test
	void allowsUpToTheLimitThenRefuses() {
		for (int i = 0; i < 3; i++)
			assertTrue(this.limiter.tryAcquire("a"), "event " + (i + 1));

		assertFalse(this.limiter.tryAcquire("a"));
		assertFalse(this.limiter.tryAcquire("a"));
	}

	@Test
	void keysAreCountedSeparately() {
		for (int i = 0; i < 3; i++)
			this.limiter.tryAcquire("a");

		assertFalse(this.limiter.tryAcquire("a"));
		assertTrue(this.limiter.tryAcquire("b"));
	}

	@Test
	void theWindowEndsAFixedTimeAfterTheFirstEventNoMatterWhatHappensInBetween() {
		this.limiter.tryAcquire("a");
		this.clock.advance(Duration.ofSeconds(6));

		// Events over the limit keep counting but must not push the end of the window further away.
		for (int i = 0; i < 10; i++)
			this.limiter.tryAcquire("a");

		this.clock.advance(Duration.ofSeconds(5));

		assertTrue(this.limiter.tryAcquire("a"), "11 seconds after the first event the window is over");
		assertEquals(1, this.limiter.count("a"));
	}

	@Test
	void hitReturnsTheRunningCount() {
		assertEquals(1, this.limiter.hit("a"));
		assertEquals(2, this.limiter.hit("a"));
		assertEquals(2, this.limiter.count("a"));
		assertEquals(0, this.limiter.count("unknown"));
	}

	@Test
	void resetForgetsTheKey() {
		this.limiter.hit("a");
		this.limiter.hit("a");
		this.limiter.reset("a");

		assertEquals(0, this.limiter.count("a"));
		assertEquals(1, this.limiter.hit("a"));
	}

	@Test
	void manyDifferentKeysDoNotBreakIt() {
		for (int i = 0; i < 5_000; i++)
			assertTrue(this.limiter.tryAcquire("key-" + i));
	}
}
