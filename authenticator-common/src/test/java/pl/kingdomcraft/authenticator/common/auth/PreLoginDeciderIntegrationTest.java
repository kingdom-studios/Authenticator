package pl.kingdomcraft.authenticator.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Decision;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Mode;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Reason;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Request;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount;
import pl.kingdomcraft.authenticator.common.repository.IpBlockRepository;
import pl.kingdomcraft.authenticator.common.settings.LimitSettings;
import pl.kingdomcraft.authenticator.common.settings.LoginSettings;
import pl.kingdomcraft.authenticator.common.settings.PremiumSettings;
import pl.kingdomcraft.authenticator.common.support.MutableClock;
import pl.kingdomcraft.authenticator.common.support.ScriptedPremiumApi;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

/** The decision table: every kind of connection, against a real database and a scripted Mojang. */
class PreLoginDeciderIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("test");
	private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
	private final ScriptedPremiumApi api = new ScriptedPremiumApi();
	private final SessionManager sessions = new SessionManager();

	private TestMariaDb.Schema schema;
	private AccountRepository accounts;
	private IpBlockRepository blocks;
	private InetAddress ip;
	private InetAddress otherIp;

	@BeforeEach
	void setUp() throws Exception {
		this.schema = TestMariaDb.createSchema(true);
		this.accounts = new AccountRepository(this.schema.database());
		this.blocks = new IpBlockRepository(this.schema.database());
		this.ip = InetAddress.getByName("203.0.113.7");
		this.otherIp = InetAddress.getByName("203.0.113.8");
		this.api.answers.put("Notch", Answer.found(NOTCH));
		this.api.answers.put("Free", Answer.notFound());
		this.api.answers.put("Newcomer", Answer.notFound());
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.schema != null)
			this.schema.close();
	}

	private PreLoginDecider decider(PremiumPolicy policy) {
		return new PreLoginDecider(new LoginSettings(Duration.ofSeconds(60), 3, Duration.ofMinutes(60), Pattern.compile("^[a-zA-Z0-9_]{3,16}$")),
				new LimitSettings(5, Duration.ofSeconds(10), 3, Duration.ofHours(168), 8, Duration.ofSeconds(300), Duration.ofMinutes(30)),
				new PremiumSettings(policy, List.of("mojang"), Duration.ofSeconds(3), PremiumResolver.Timing.defaults(), Duration.ofSeconds(60)), this.accounts, this.blocks,
				new PremiumResolver(List.of(this.api), null, PremiumResolver.Timing.defaults(), this.clock, LOGGER), this.sessions, this.clock, LOGGER);
	}

	private Request request(String name) {
		return new Request(name, this.ip, null);
	}

	private static void assertDecision(Mode mode, Reason reason, Decision decision) {
		assertDecision(mode, reason, decision, "");
	}

	private static void assertDecision(Mode mode, Reason reason, Decision decision, String why) {
		assertEquals(mode, decision.mode(), why + " " + decision);
		assertEquals(reason, decision.reason(), why + " " + decision);
	}

	private void cracked(String name) throws Exception {
		this.accounts.insert(new NewAccount(UUID.randomUUID(), name, false, null, "$2a$06$h", null, HashAlgo.BCRYPT, null));
	}

	private void premium(String name, UUID mojang) throws Exception {
		this.accounts.insert(new NewAccount(mojang, name, true, mojang, null, null, null, null));
	}

	// ---- new names ----

	@Test
	void aNewNameThatIsNotAnOriginalOneIsOfflineAndMustRegister() {
		assertDecision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, this.decider(PremiumPolicy.PROTECT).decide(this.request("Newcomer")));
	}

	@Test
	void aNewOriginalNameIsOnlineSoMojangProvesTheOwner() {
		final Decision decision = this.decider(PremiumPolicy.PROTECT).decide(this.request("Notch"));

		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decision);
		assertEquals("scripted", decision.source());
	}

	@Test
	void underProtectAnOriginalNameStaysOnlineHoweverOftenAndHoweverTheClientAnnouncesItself() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.PROTECT);

		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(this.request("Notch")));
		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(this.request("Notch")));
		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(new Request("Notch", this.ip, UUID.randomUUID())), "a launcher's own UUID must not talk it out of protection");
	}

	@Test
	void nobodyElseCanUseARegisteredNameInAnotherCase() throws Exception {
		this.cracked("Steve");
		this.premium("Alex", UUID.randomUUID());

		final Decision steve = this.decider(PremiumPolicy.PROTECT).decide(this.request("steve"));

		assertDecision(Mode.DENY, Reason.WRONG_CASE, steve);
		assertEquals("Steve", steve.detail());
		assertDecision(Mode.DENY, Reason.WRONG_CASE, this.decider(PremiumPolicy.PROTECT).decide(this.request("ALEX")));
	}

	@Test
	void anUnknownNameWithNoLookupServiceAnsweringIsRefusedNeverGuessed() {
		this.api.answers.clear();

		assertDecision(Mode.DENY, Reason.PREMIUM_LOOKUP_FAILED, this.decider(PremiumPolicy.PROTECT).decide(this.request("Mystery")));
	}

	// ---- existing accounts ----

	@Test
	void aRegisteredNonPremiumAccountIsOfflineWithoutAskingMojang() throws Exception {
		this.cracked("Steve");

		assertDecision(Mode.OFFLINE, Reason.REGISTERED_ACCOUNT, this.decider(PremiumPolicy.PROTECT).decide(this.request("Steve")));
		assertEquals(0, this.api.calls.get());
	}

	@Test
	void aPremiumAccountIsOnlineWithoutAskingMojang() throws Exception {
		this.premium("Notch", NOTCH);

		assertDecision(Mode.ONLINE, Reason.PREMIUM_ACCOUNT, this.decider(PremiumPolicy.PROTECT).decide(this.request("Notch")));
		assertEquals(0, this.api.calls.get());
	}

	@Test
	void anOriginalPlayerWhoChangedTheirNameStillOwnsTheirAccount() throws Exception {
		this.premium("OldName", NOTCH);
		this.api.answers.put("NewName", Answer.found(NOTCH));

		final Decision decision = this.decider(PremiumPolicy.PROTECT).decide(this.request("NewName"));

		assertDecision(Mode.ONLINE, Reason.RENAMED_PREMIUM, decision);
		assertEquals("OldName", decision.detail());
	}

	// ---- ALLOW_CLAIM_AUTO ----

	@Test
	void underClaimAutoAnOriginalNameIsTriedOnlineOnceAndThenOffline() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.ALLOW_CLAIM_AUTO);

		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(this.request("Notch")));
		assertDecision(Mode.OFFLINE, Reason.CLAIM_FAILED, decider.decide(this.request("Notch")), "the online attempt did not complete, so the client cannot be an original one");
	}

	@Test
	void theOnlineAttemptIsPerAddressSoAnotherPlayerIsNotForcedOffline() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.ALLOW_CLAIM_AUTO);

		decider.decide(this.request("Notch"));

		assertDecision(Mode.OFFLINE, Reason.CLAIM_FAILED, decider.decide(this.request("Notch")));
		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(new Request("Notch", this.otherIp, null)), "someone else's failed attempt must not lock the real owner out");
	}

	@Test
	void aSuccessfulOnlineLoginClearsTheAttempt() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.ALLOW_CLAIM_AUTO);

		decider.decide(this.request("Notch"));
		decider.claimSucceeded("Notch", this.ip);

		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(this.request("Notch")));
	}

	@Test
	void theAttemptIsForgottenAfterTheClaimWindow() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.ALLOW_CLAIM_AUTO);

		decider.decide(this.request("Notch"));
		this.clock.advance(Duration.ofSeconds(61));

		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(this.request("Notch")));
	}

	@Test
	void aLauncherThatAnnouncesAnotherUuidIsOfflineAtOnce() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.ALLOW_CLAIM_AUTO);

		assertDecision(Mode.OFFLINE, Reason.LAUNCHER_UUID, decider.decide(new Request("Notch", this.ip, UUID.randomUUID())));
		assertDecision(Mode.ONLINE, Reason.ORIGINAL_NAME, decider.decide(new Request("Notch", this.ip, NOTCH)), "the real owner's UUID takes the online path");
	}

	// ---- the gates in front ----

	@Test
	void aNameThatCannotBeUsedIsRefusedBeforeAnythingIsAsked() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.PROTECT);

		for (final String bad : new String[] { "ab", "has space", "x".repeat(17), "../etc", "name;DROP", "", "zażółć" })
			assertDecision(Mode.DENY, Reason.BAD_NICKNAME, decider.decide(new Request(bad, this.otherIp(bad), null)));

		assertEquals(0, this.api.calls.get());
	}

	private InetAddress otherIp(String seed) {
		try {
			return InetAddress.getByAddress(new byte[] { (byte) 198, 51, 100, (byte) (Math.abs(seed.hashCode()) % 200 + 1) });
		} catch (final Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void anAddressThatConnectsTooOftenIsRefusedWhileOthersAreNot() {
		final PreLoginDecider decider = this.decider(PremiumPolicy.PROTECT);

		for (int i = 0; i < 5; i++)
			assertDecision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, decider.decide(this.request("Newcomer")));

		assertDecision(Mode.DENY, Reason.TOO_MANY_CONNECTIONS, decider.decide(this.request("Newcomer")));
		assertDecision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, decider.decide(new Request("Newcomer", this.otherIp, null)));

		this.clock.advance(Duration.ofSeconds(11));

		assertDecision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, decider.decide(this.request("Newcomer")));
	}

	@Test
	void aBlockedAddressIsRefusedUntilTheBlockEnds() throws Exception {
		final PreLoginDecider decider = this.decider(PremiumPolicy.PROTECT);
		final Instant until = this.clock.instant().plus(Duration.ofMinutes(30));

		this.blocks.block(this.ip, until, "too many wrong passwords");

		final Decision decision = decider.decide(this.request("Newcomer"));

		assertDecision(Mode.DENY, Reason.IP_BLOCKED, decision);
		assertEquals(until.toString(), decision.detail());

		this.clock.advance(Duration.ofMinutes(31));

		assertDecision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, decider.decide(new Request("Newcomer", this.ip, null)));
	}

	@Test
	void aBlockIsNoticedAtOnceWhenTheDeciderIsTold_andOtherwiseAfterTheCacheExpires() throws Exception {
		final PreLoginDecider decider = this.decider(PremiumPolicy.PROTECT);

		assertDecision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, decider.decide(this.request("Newcomer")));

		final Instant until = this.clock.instant().plus(Duration.ofMinutes(30));
		this.blocks.block(this.ip, until, "x");

		assertDecision(Mode.OFFLINE, Reason.NEW_NON_PREMIUM, decider.decide(this.request("Newcomer")), "still the cached 'not blocked'");

		decider.noteBlocked(this.ip, until);

		assertDecision(Mode.DENY, Reason.IP_BLOCKED, decider.decide(this.request("Newcomer")));
	}

	@Test
	void aSecondConnectionWithTheSameNameWhileOnlineIsRefused() throws Exception {
		this.sessions.add(new PlayerSession(UUID.randomUUID(), "Steve", this.ip, this.clock.instant(), this.clock.instant().plusSeconds(60), false, AuthState.PENDING_LOGIN, null, null));
		this.cracked("Steve");

		assertDecision(Mode.DENY, Reason.ALREADY_ONLINE, this.decider(PremiumPolicy.PROTECT).decide(new Request("Steve", this.otherIp, null)));
		assertDecision(Mode.DENY, Reason.ALREADY_ONLINE, this.decider(PremiumPolicy.PROTECT).decide(new Request("steve", InetAddress.getByName("198.51.100.1"), null)));
	}

	@Test
	void aBrokenDatabaseRefusesTheConnectionNeverLetsAnyoneIn() throws Exception {
		this.cracked("Steve");

		final PreLoginDecider decider = this.decider(PremiumPolicy.PROTECT);

		this.schema.database().close();

		assertDecision(Mode.DENY, Reason.DATABASE_ERROR, decider.decide(new Request("Steve", this.otherIp, null)));
	}
}
