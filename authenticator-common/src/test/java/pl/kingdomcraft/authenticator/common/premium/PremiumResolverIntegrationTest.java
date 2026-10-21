package pl.kingdomcraft.authenticator.common.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver.Result;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver.Status;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver.Timing;
import pl.kingdomcraft.authenticator.common.repository.PremiumCacheRepository;
import pl.kingdomcraft.authenticator.common.support.MutableClock;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

/** The database cache: what survives a restart and what carries the proxy through an outage of the lookup services. */
class PremiumResolverIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("test");
	private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
	private final AtomicInteger calls = new AtomicInteger();
	private volatile Answer notchAnswer = Answer.found(NOTCH);
	private volatile Answer freeAnswer = Answer.notFound();

	private TestMariaDb.Schema schema;
	private PremiumCacheRepository cache;

	private final PremiumApi api = new PremiumApi() {
		@Override
		public String name() {
			return "scripted";
		}

		@Override
		public Answer lookup(String username) {
			PremiumResolverIntegrationTest.this.calls.incrementAndGet();

			return switch (username) {
				case "Notch" -> PremiumResolverIntegrationTest.this.notchAnswer;
				case "Free" -> PremiumResolverIntegrationTest.this.freeAnswer;
				default -> Answer.unavailable("down");
			};
		}
	};

	@BeforeEach
	void setUp() throws Exception {
		this.schema = TestMariaDb.createSchema(true);
		this.cache = new PremiumCacheRepository(this.schema.database());
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.schema != null)
			this.schema.close();
	}

	/** Every resolver is a fresh process: its memory is empty, only the database is shared. */
	private PremiumResolver freshResolver() {
		return new PremiumResolver(List.of(this.api), this.cache, Timing.defaults(), this.clock, LOGGER);
	}

	@Test
	void anAnswerSurvivesARestartThroughTheDatabase() {
		assertEquals("scripted", this.freshResolver().resolve("Notch").source());

		final Result afterRestart = this.freshResolver().resolve("Notch");

		assertEquals(Status.PREMIUM, afterRestart.status());
		assertEquals(NOTCH, afterRestart.uuid());
		assertEquals("database", afterRestart.source());
		assertEquals(1, this.calls.get());
	}

	@Test
	void aPositiveAnswerIsTrustedForADayAndANegativeOneForAnHour() {
		this.freshResolver().resolve("Notch");
		this.freshResolver().resolve("Free");
		assertEquals(2, this.calls.get());

		this.clock.advance(Duration.ofMinutes(59));
		this.freshResolver().resolve("Notch");
		this.freshResolver().resolve("Free");
		assertEquals(2, this.calls.get(), "both still fresh");

		this.clock.advance(Duration.ofMinutes(2));
		this.freshResolver().resolve("Notch");
		this.freshResolver().resolve("Free");
		assertEquals(3, this.calls.get(), "only the negative answer (an hour) went stale");

		this.clock.advance(Duration.ofHours(23));
		this.freshResolver().resolve("Notch");
		assertEquals(4, this.calls.get(), "the positive answer (a day) went stale too");
	}

	@Test
	void aStaleAnswerCarriesTheProxyThroughAnOutage() {
		this.freshResolver().resolve("Notch");
		this.freshResolver().resolve("Free");

		this.clock.advance(Duration.ofDays(3));
		this.notchAnswer = Answer.unavailable("down");
		this.freeAnswer = Answer.unavailable("down");

		final Result notch = this.freshResolver().resolve("Notch");
		final Result free = this.freshResolver().resolve("Free");

		assertEquals(Status.PREMIUM, notch.status());
		assertEquals("database-stale", notch.source());
		assertEquals(Status.CRACKED, free.status());
		assertEquals("database-stale", free.source());
	}

	@Test
	void aNameNeverSeenBeforeIsUnknownDuringAnOutage() {
		this.notchAnswer = Answer.unavailable("down");

		assertEquals(Status.UNKNOWN, this.freshResolver().resolve("Notch").status());
	}

	@Test
	void aNotFoundAnswerIsStoredWithoutAUuid() throws Exception {
		this.freshResolver().resolve("Free");

		assertNull(this.cache.find("Free").orElseThrow().premiumUuid());
	}

	@Test
	void aBrokenDatabaseCostsTheCacheButNotTheLookup() throws Exception {
		final PremiumResolver resolver = this.freshResolver();

		this.schema.database().close();

		final Result result = resolver.resolve("Notch");

		assertEquals(Status.PREMIUM, result.status());
		assertEquals("scripted", result.source());
	}
}
