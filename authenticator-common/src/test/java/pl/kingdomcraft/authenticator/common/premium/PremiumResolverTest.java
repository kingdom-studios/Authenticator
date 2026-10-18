package pl.kingdomcraft.authenticator.common.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver.Result;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver.Status;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver.Timing;
import pl.kingdomcraft.authenticator.common.support.MutableClock;

class PremiumResolverTest {

	private static final Logger LOGGER = Logger.getLogger("test");
	private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

	/** A lookup service that answers from a map and counts how often it was asked. */
	private static final class ScriptedApi implements PremiumApi {

		final String name;
		final Map<String, Answer> answers = new ConcurrentHashMap<>();
		final AtomicInteger calls = new AtomicInteger();
		volatile Answer fallback = Answer.unavailable("down");
		volatile long delayMs;

		ScriptedApi(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public Answer lookup(String username) {
			this.calls.incrementAndGet();

			if (this.delayMs > 0)
				try {
					Thread.sleep(this.delayMs);
				} catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
				}

			return this.answers.getOrDefault(username, this.fallback);
		}
	}

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
	private final ScriptedApi first = new ScriptedApi("first");
	private final ScriptedApi second = new ScriptedApi("second");

	private PremiumResolver resolver(PremiumApi... apis) {
		return new PremiumResolver(List.of(apis), null, Timing.defaults(), this.clock, LOGGER);
	}

	@Test
	void theFirstDefiniteAnswerWinsAndLaterServicesAreNotAsked() {
		this.first.answers.put("Notch", Answer.found(NOTCH));
		this.second.answers.put("Notch", Answer.notFound());

		final Result result = this.resolver(this.first, this.second).resolve("Notch");

		assertEquals(Status.PREMIUM, result.status());
		assertEquals(NOTCH, result.uuid());
		assertEquals("first", result.source());
		assertEquals(0, this.second.calls.get());
	}

	@Test
	void aNotFoundAnswerIsDefiniteToo() {
		this.first.answers.put("Free", Answer.notFound());
		this.second.answers.put("Free", Answer.found(NOTCH));

		final Result result = this.resolver(this.first, this.second).resolve("Free");

		assertEquals(Status.CRACKED, result.status());
		assertNull(result.uuid());
		assertEquals(0, this.second.calls.get());
	}

	@Test
	void aServiceThatIsDownIsSkipped() {
		this.second.answers.put("Notch", Answer.found(NOTCH));

		final Result result = this.resolver(this.first, this.second).resolve("Notch");

		assertEquals(Status.PREMIUM, result.status());
		assertEquals("second", result.source());
		assertEquals(1, this.first.calls.get());
	}

	@Test
	void whenNoServiceAnswersTheStatusIsUnknownNeverAGuess() {
		final Result result = this.resolver(this.first, this.second).resolve("Notch");

		assertEquals(Status.UNKNOWN, result.status());
		assertNull(result.uuid());
	}

	@Test
	void withoutAnyServiceConfiguredNothingIsKnown() {
		assertEquals(Status.UNKNOWN, this.resolver().resolve("Notch").status());
	}

	@Test
	void anAnswerIsRememberedInMemoryIgnoringCaseUntilItExpires() {
		this.first.answers.put("Notch", Answer.found(NOTCH));

		final PremiumResolver resolver = this.resolver(this.first);

		assertEquals("first", resolver.resolve("Notch").source());
		assertEquals("memory", resolver.resolve("Notch").source());
		assertEquals("memory", resolver.resolve("NOTCH").source());
		assertEquals(1, this.first.calls.get());

		this.clock.advance(Duration.ofMinutes(9));
		assertEquals("memory", resolver.resolve("notch").source());

		this.clock.advance(Duration.ofMinutes(2));
		assertEquals("first", resolver.resolve("Notch").source());
		assertEquals(2, this.first.calls.get());
	}

	@Test
	void aFailureToAnswerIsRememberedOnlyBrieflySoTheServicesAreTriedAgainSoon() {
		final PremiumResolver resolver = this.resolver(this.first);

		assertEquals(Status.UNKNOWN, resolver.resolve("Notch").status());
		assertEquals(Status.UNKNOWN, resolver.resolve("Notch").status());
		assertEquals(1, this.first.calls.get(), "a burst must not hammer a service that is down");

		this.first.answers.put("Notch", Answer.found(NOTCH));
		this.clock.advance(Duration.ofSeconds(16));

		assertEquals(Status.PREMIUM, resolver.resolve("Notch").status(), "the service is asked again once the pause is over");
	}

	@Test
	void manyConnectionsWithTheSameNameCauseOneRequest() throws Exception {
		this.first.answers.put("Notch", Answer.found(NOTCH));
		this.first.delayMs = 300;

		final PremiumResolver resolver = this.resolver(this.first);
		final CountDownLatch go = new CountDownLatch(1);
		final List<CompletableFuture<Result>> results = new ArrayList<>();

		for (int i = 0; i < 20; i++)
			results.add(CompletableFuture.supplyAsync(() -> {
				try {
					go.await();
				} catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
				}

				return resolver.resolve("Notch");
			}));

		go.countDown();

		for (final CompletableFuture<Result> result : results)
			assertEquals(NOTCH, result.get().uuid());

		assertEquals(1, this.first.calls.get());
	}

	@Test
	void differentNamesAreLookedUpIndependently() {
		this.first.answers.put("Notch", Answer.found(NOTCH));
		this.first.answers.put("Free", Answer.notFound());

		final PremiumResolver resolver = this.resolver(this.first);

		assertEquals(Status.PREMIUM, resolver.resolve("Notch").status());
		assertEquals(Status.CRACKED, resolver.resolve("Free").status());
		assertEquals(2, this.first.calls.get());
	}

	@Test
	void forgettingAName_makesTheNextLookupAskAgain() {
		this.first.answers.put("Notch", Answer.notFound());

		final PremiumResolver resolver = this.resolver(this.first);

		assertEquals(Status.CRACKED, resolver.resolve("Notch").status());

		this.first.answers.put("Notch", Answer.found(NOTCH));
		resolver.forget("NOTCH");

		assertEquals(Status.PREMIUM, resolver.resolve("Notch").status());
		assertTrue(this.first.calls.get() >= 2);
	}
}
