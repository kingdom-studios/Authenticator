package pl.kingdomcraft.authenticator.common.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Kind;
import pl.kingdomcraft.authenticator.common.support.FakeHttpServer;
import pl.kingdomcraft.authenticator.common.support.FakeHttpServer.Reply;

class HttpPremiumApiTest {

	private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
	private static final Duration TIMEOUT = Duration.ofMillis(800);

	private FakeHttpServer server;
	private PremiumApi mojang;
	private PremiumApi ashcon;
	private PremiumApi minetools;

	@BeforeEach
	void setUp() throws Exception {
		this.server = new FakeHttpServer();

		final var client = HttpPremiumApi.newClient(TIMEOUT);

		this.mojang = HttpPremiumApi.mojang(client, TIMEOUT, this.server.url() + "mojang/");
		this.ashcon = HttpPremiumApi.ashcon(client, TIMEOUT, this.server.url() + "ashcon/");
		this.minetools = HttpPremiumApi.minetools(client, TIMEOUT, this.server.url() + "minetools/");
	}

	@AfterEach
	void tearDown() {
		this.server.close();
	}

	private static void assertKind(Kind expected, Answer answer) {
		assertEquals(expected, answer.kind(), answer.detail());
	}

	// ---- Mojang ----

	@Test
	void mojangFoundReturnsTheDashedUuidAndIgnoresCaseOfTheName() {
		this.server.reply("/mojang/Notch", Reply.ok("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}"));
		this.server.reply("/mojang/notch", Reply.ok("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}"));

		assertEquals(NOTCH, this.mojang.lookup("Notch").uuid());
		assertKind(Kind.FOUND, this.mojang.lookup("notch"));
	}

	@Test
	void mojangSaysNotFoundWith404And204() {
		this.server.reply("/mojang/Free", Reply.status(404));
		this.server.reply("/mojang/Empty", Reply.status(204));

		assertKind(Kind.NOT_FOUND, this.mojang.lookup("Free"));
		assertKind(Kind.NOT_FOUND, this.mojang.lookup("Empty"));
	}

	@Test
	void mojangRateLimitsAndErrorsAreNotAnAnswer() {
		this.server.reply("/mojang/Busy", Reply.status(429));
		this.server.reply("/mojang/Broken", Reply.status(500));
		this.server.reply("/mojang/Down", Reply.status(503));

		for (final String name : new String[] { "Busy", "Broken", "Down" })
			assertKind(Kind.UNAVAILABLE, this.mojang.lookup(name));
	}

	@Test
	void anAnswerForAnotherNameDoesNotCount() {
		// A service may answer for a name the account used to have.
		this.server.reply("/mojang/OldName", Reply.ok("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"NewName\"}"));

		assertKind(Kind.NOT_FOUND, this.mojang.lookup("OldName"));
	}

	@Test
	void garbageIsNotAnAnswer() {
		this.server.reply("/mojang/NoId", Reply.ok("{\"name\":\"NoId\"}"));
		this.server.reply("/mojang/NotJson", Reply.ok("<html>maintenance</html>"));
		this.server.reply("/mojang/BadId", Reply.ok("{\"id\":\"not-a-uuid\",\"name\":\"BadId\"}"));
		this.server.reply("/mojang/Array", Reply.ok("[1,2,3]"));
		this.server.reply("/mojang/IdObject", Reply.ok("{\"id\":{\"x\":1},\"name\":\"IdObject\"}"));

		for (final String name : new String[] { "NoId", "NotJson", "BadId", "Array", "IdObject" })
			assertKind(Kind.UNAVAILABLE, this.mojang.lookup(name));
	}

	@Test
	void aSlowServiceIsGivenUpOn() {
		this.server.reply("/mojang/Slow", Reply.slow("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Slow\"}", 3_000));

		final long start = System.nanoTime();

		assertKind(Kind.UNAVAILABLE, this.mojang.lookup("Slow"));
		assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500, "must not wait for the slow answer");
	}

	@Test
	void aRedirectIsNeverFollowed() {
		this.server.reply("/mojang/Moved", Reply.redirect(this.server.url() + "mojang/Notch"));
		this.server.reply("/mojang/Notch", Reply.ok("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}"));

		assertKind(Kind.UNAVAILABLE, this.mojang.lookup("Moved"));
		assertEquals(1, this.server.requestCount(), "the redirect target must not be requested");
	}

	@Test
	void anOversizedAnswerIsNotAnAnswer() {
		this.server.reply("/mojang/Huge", Reply.ok("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Huge\",\"pad\":\"" + "x".repeat(70_000) + "\"}"));

		assertKind(Kind.UNAVAILABLE, this.mojang.lookup("Huge"));
	}

	@Test
	void anUnreachableServiceIsUnavailable() {
		final PremiumApi dead = HttpPremiumApi.mojang(HttpPremiumApi.newClient(TIMEOUT), TIMEOUT, "http://127.0.0.1:1/");

		assertKind(Kind.UNAVAILABLE, dead.lookup("Notch"));
	}

	@Test
	void aNameThatCannotBeAMinecraftNameNeverReachesTheNetwork() {
		for (final String name : new String[] { "../admin", "a b", "name?x=1", "", "x".repeat(17), "zażółć", "a/b" })
			assertKind(Kind.NOT_FOUND, this.mojang.lookup(name));

		assertEquals(0, this.server.requestCount());
	}

	@Test
	void everyRequestIdentifiesItself() {
		this.server.reply("/mojang/Notch", Reply.status(404));

		this.mojang.lookup("Notch");

		assertEquals("/mojang/Notch|Authenticator", this.server.requests().get(0));
	}

	// ---- Ashcon ----

	@Test
	void ashconUsesTheUuidAndUsernameFields() {
		this.server.reply("/ashcon/Notch", Reply.ok("{\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"username\":\"Notch\"}"));
		this.server.reply("/ashcon/Free", Reply.status(404));
		this.server.reply("/ashcon/Renamed", Reply.ok("{\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"username\":\"SomethingElse\"}"));
		this.server.reply("/ashcon/Busy", Reply.status(429));

		assertEquals(NOTCH, this.ashcon.lookup("Notch").uuid());
		assertKind(Kind.NOT_FOUND, this.ashcon.lookup("Free"));
		assertKind(Kind.NOT_FOUND, this.ashcon.lookup("Renamed"));
		assertKind(Kind.UNAVAILABLE, this.ashcon.lookup("Busy"));
	}

	// ---- MineTools ----

	@Test
	void minetoolsWordsItsAnswersInAStatusField() {
		this.server.reply("/minetools/Notch", Reply.ok("{\"status\":\"OK\",\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}"));
		this.server.reply("/minetools/Free", Reply.ok("{\"status\":\"ERR\",\"message\":\"Couldn't find any profile with that name\"}"));
		this.server.reply("/minetools/Failing", Reply.ok("{\"status\":\"ERR\",\"message\":\"Mojang is on fire\"}"));
		this.server.reply("/minetools/Busy", Reply.status(502));

		assertEquals(NOTCH, this.minetools.lookup("Notch").uuid());
		assertKind(Kind.NOT_FOUND, this.minetools.lookup("Free"));
		assertKind(Kind.UNAVAILABLE, this.minetools.lookup("Failing"));
		assertKind(Kind.UNAVAILABLE, this.minetools.lookup("Busy"));
	}

	// ---- helpers ----

	@Test
	void theDefaultServicesAreCreatedInTheOrderGiven() {
		final List<PremiumApi> apis = HttpPremiumApi.create(List.of("ashcon", " Mojang ", "minetools"), HttpPremiumApi.newClient(TIMEOUT), TIMEOUT);

		assertEquals(List.of("ashcon", "mojang", "minetools"), apis.stream().map(PremiumApi::name).toList());
		assertThrows(IllegalArgumentException.class, () -> HttpPremiumApi.create(List.of("mojang", "bing"), HttpPremiumApi.newClient(TIMEOUT), TIMEOUT));
	}

	@Test
	void uuidsAreParsedWithOrWithoutDashes() {
		assertEquals(NOTCH, HttpPremiumApi.parseUuid("069a79f444e94726a5befca90e38aaf5"));
		assertEquals(NOTCH, HttpPremiumApi.parseUuid("069A79F4-44E9-4726-A5BE-FCA90E38AAF5"));
		assertEquals(null, HttpPremiumApi.parseUuid("069a79f444e94726a5befca90e38aaf"));
		assertEquals(null, HttpPremiumApi.parseUuid("zz9a79f444e94726a5befca90e38aaf5"));
		assertEquals(null, HttpPremiumApi.parseUuid(""));
	}
}
