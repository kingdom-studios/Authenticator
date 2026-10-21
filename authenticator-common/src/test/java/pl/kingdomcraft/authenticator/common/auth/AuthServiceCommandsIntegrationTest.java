package pl.kingdomcraft.authenticator.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.NOTCH;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.PASSWORD;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.waitFor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthService.Outcome;
import pl.kingdomcraft.authenticator.common.auth.AuthService.Result;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount;
import pl.kingdomcraft.authenticator.common.support.AuthFixture;

class AuthServiceCommandsIntegrationTest {

	private static final String SALT = "Zk3pQ9";
	private static final String SHA256_SALTED = "fcf57a58b2120bbde39ee8cf358ae073c3305f0fb655eabc5068ab70213a2f55"; // "hunter2" + SALT, made by hashlib

	private AuthFixture f;

	@BeforeEach
	void setUp() throws Exception {
		this.f = new AuthFixture();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.f != null)
			this.f.close();
	}

	private static Result get(CompletableFuture<Result> future) throws Exception {
		return future.get(10, TimeUnit.SECONDS);
	}

	private static void assertOutcome(Outcome expected, Result result) {
		assertOutcome(expected, result, "");
	}

	private static void assertOutcome(Outcome expected, Result result, String why) {
		assertEquals(expected, result.outcome(), why + " " + result);
	}

	private Account account(String name) throws Exception {
		return this.f.accounts.findByUsername(name).orElseThrow();
	}

	private int audit(String event) throws Exception {
		return this.f.count("SELECT COUNT(*) FROM auth_audit WHERE event = '" + event + "'");
	}

	// ---- register ----

	@Test
	void registeringCreatesTheAccountAndLogsThePlayerIn() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		assertOutcome(Outcome.OK, get(this.f.service.register(session, PASSWORD, PASSWORD)));

		final Account account = this.account("Steve");

		assertFalse(account.premium());
		assertEquals(session.uuid(), account.uuid());
		assertEquals(HashAlgo.BCRYPT, account.hashAlgo());
		assertTrue(account.passwordHash().startsWith("$2a$04$"), account.passwordHash());
		assertEquals(this.f.ip, account.registeredIp());
		assertTrue(this.f.hasher.verify(account.hashAlgo(), account.passwordHash(), null, PASSWORD));
		assertFalse(account.passwordHash().contains(PASSWORD), "the password must never be stored");

		assertEquals(AuthState.AUTHENTICATED, session.state());
		assertEquals(AuthMethod.PASSWORD, session.method());
		assertEquals(account.id(), session.account().id());

		assertEquals(AuthMethod.PASSWORD, this.f.presence.find(session.uuid()).orElseThrow().method());
		assertTrue(this.f.ipSessions.findValid(account.id(), this.f.ip, this.f.clock.instant()).isPresent());
		assertEquals(1, this.audit("REGISTER"));
		assertEquals(2, this.f.notifications.size(), "once when waiting to register, once when logged in");
		assertEquals("Steve:AUTHENTICATED:PASSWORD:2", this.f.notifications.get(1));
	}

	@Test
	void registeringRefusesMismatchedAndWeakPasswords() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		assertOutcome(Outcome.PASSWORD_MISMATCH, get(this.f.service.register(session, PASSWORD, "something-else")));

		final String[][] weak = { { "short", "TOO_SHORT:6" }, { "has space inside", "WHITESPACE" }, { "x".repeat(73), "TOO_LONG" }, { "steve", "TOO_SHORT:6" }, { "Steve1", "FORBIDDEN_OR_OK" },
				{ "PASSWORD123", "FORBIDDEN" }, { "ZAŻÓŁĆ".repeat(13), "TOO_LONG" } };

		for (final String[] entry : weak) {
			if (entry[1].equals("FORBIDDEN_OR_OK"))
				continue;

			final Result result = get(this.f.service.register(session, entry[0], entry[0]));

			assertOutcome(Outcome.WEAK_PASSWORD, result);
			assertEquals(entry[1], result.detail(), entry[0]);
		}

		assertEquals(0, this.f.accounts.count());
		assertEquals(AuthState.PENDING_REGISTER, session.state());
	}

	@Test
	void aPasswordEqualToTheNameIsRefused() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve_Long_Name");

		assertEquals("SAME_AS_NAME", get(this.f.service.register(session, "STEVE_LONG_NAME", "STEVE_LONG_NAME")).detail());
	}

	@Test
	void registeringTwiceOrWhileLoggedInIsRefused() throws Exception {
		final PlayerSession registered = this.f.newPlayer("Steve");

		get(this.f.service.register(registered, PASSWORD, PASSWORD));

		assertOutcome(Outcome.ALREADY_LOGGED_IN, get(this.f.service.register(registered, PASSWORD, PASSWORD)));

		final PlayerSession returning = this.f.returningPlayer("Alex");

		assertOutcome(Outcome.ALREADY_REGISTERED, get(this.f.service.register(returning, PASSWORD, PASSWORD)));
	}

	@Test
	void oneAddressMayRegisterOnlyAFewAccounts() throws Exception {
		for (final String name : new String[] { "One", "Two", "Three" })
			assertOutcome(Outcome.OK, get(this.f.service.register(this.f.newPlayer(name), PASSWORD, PASSWORD)));

		final Result refused = get(this.f.service.register(this.f.newPlayer("Four"), PASSWORD, PASSWORD));

		assertOutcome(Outcome.TOO_MANY_ACCOUNTS, refused);
		assertEquals("3", refused.detail());
		assertTrue(this.f.accounts.findByUsername("Four").isEmpty());

		assertOutcome(Outcome.OK, get(this.f.service.register(this.f.service.open("Five", UUID.randomUUID(), this.f.otherIp, false).session(), PASSWORD, PASSWORD)), "another address is unaffected");

		this.f.clock.advance(Duration.ofHours(169));

		assertOutcome(Outcome.OK, get(this.f.service.register(this.f.sessions.findByName("Four").orElseThrow(), PASSWORD, PASSWORD)), "the window is over");
	}

	@Test
	void aNameTakenWhileThePlayerWasTypingIsReportedAndNothingChanges() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		this.f.accounts.insert(new NewAccount(UUID.randomUUID(), "Steve", false, null, "$2a$04$h", null, HashAlgo.BCRYPT, null));

		assertOutcome(Outcome.NAME_TAKEN, get(this.f.service.register(session, PASSWORD, PASSWORD)));
		assertEquals(AuthState.PENDING_REGISTER, session.state());
		assertEquals(1, this.f.accounts.count());
	}

	@Test
	void anOriginalPlayerHasNothingToRegister() throws Exception {
		assertOutcome(Outcome.ALREADY_LOGGED_IN, get(this.f.service.register(this.f.premiumPlayer("Notch", NOTCH), PASSWORD, PASSWORD)));
	}

	// ---- login ----

	@Test
	void theRightPasswordLogsThePlayerIn() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		assertOutcome(Outcome.OK, get(this.f.service.login(session, PASSWORD)));

		assertEquals(AuthState.AUTHENTICATED, session.state());
		assertEquals(AuthMethod.PASSWORD, session.method());
		assertEquals(AuthMethod.PASSWORD, this.f.presence.find(session.uuid()).orElseThrow().method());
		assertNull(this.f.accounts.findByUsername("Steve").orElseThrow().passwordSalt());
		assertEquals(1, this.audit("LOGIN_SUCCESS"));
		assertTrue(this.f.ipSessions.findValid(session.account().id(), this.f.ip, this.f.clock.instant()).isPresent());
	}

	@Test
	void aWrongPasswordCountsDownTheAttemptsAndTheLastOneKicks() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		final Result first = get(this.f.service.login(session, "wrong-one"));
		final Result second = get(this.f.service.login(session, "wrong-two"));
		final Result third = get(this.f.service.login(session, "wrong-three"));

		assertOutcome(Outcome.WRONG_PASSWORD, first);
		assertEquals("2", first.detail());
		assertOutcome(Outcome.WRONG_PASSWORD, second);
		assertEquals("1", second.detail());
		assertOutcome(Outcome.TOO_MANY_ATTEMPTS, third);
		assertEquals(AuthState.PENDING_LOGIN, session.state());
		assertEquals(3, this.audit("LOGIN_FAILED"));
	}

	@Test
	void aSuccessfulLoginForgivesEarlierMistakes() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		get(this.f.service.login(session, "wrong"));
		get(this.f.service.login(session, "wrong"));
		assertOutcome(Outcome.OK, get(this.f.service.login(session, PASSWORD)));
		assertEquals(0, session.failedAttempts());
	}

	@Test
	void manyWrongPasswordsFromOneAddressBlockTheAddress() throws Exception {
		final List<PlayerSession> players = new ArrayList<>();

		for (final String name : new String[] { "A1", "A2", "A3", "A4" })
			players.add(this.f.registeredPlayer(name, this.f.ip));

		Result last = null;
		int attempts = 0;

		// 8 wrong passwords from this address within the window, spread over several visits (3 per visit).
		outer: for (final PlayerSession player : players)
			for (int i = 0; i < 3; i++) {
				last = get(this.f.service.login(player, "wrong-" + i));
				attempts++;

				if (last.outcome() == Outcome.IP_BLOCKED)
					break outer;
			}

		assertOutcome(Outcome.IP_BLOCKED, last);
		assertEquals(8, attempts);
		assertTrue(this.f.blocks.blockedUntil(this.f.ip, this.f.clock.instant()).isPresent());
		assertEquals(1, this.audit("IP_BLOCKED"));

		// The address stays blocked even for the right password, and a new connection from it is refused up front.
		final PlayerSession another = players.get(3);

		assertOutcome(Outcome.IP_BLOCKED, get(this.f.service.login(another, PASSWORD)));
		assertEquals(PreLoginDecider.Reason.IP_BLOCKED, this.f.decider.decide(new PreLoginDecider.Request("Newcomer", this.f.ip, null)).reason());
	}

	@Test
	void theBlockEndsAndThenAPlayerCanLogInAgain() throws Exception {
		final PlayerSession player = this.f.returningPlayer("Steve");

		this.f.blocks.block(this.f.ip, this.f.clock.instant().plus(Duration.ofMinutes(30)), "test");

		assertOutcome(Outcome.IP_BLOCKED, get(this.f.service.login(player, PASSWORD)));

		this.f.clock.advance(Duration.ofMinutes(31));

		assertOutcome(Outcome.OK, get(this.f.service.login(player, PASSWORD)));
	}

	@Test
	void loggingInIsRefusedInTheWrongState() throws Exception {
		assertOutcome(Outcome.NOT_REGISTERED, get(this.f.service.login(this.f.newPlayer("Steve"), PASSWORD)));
		assertOutcome(Outcome.ALREADY_LOGGED_IN, get(this.f.service.login(this.f.loggedInPlayer("Alex"), PASSWORD)));
	}

	@Test
	void aPasswordChangedBehindTheBackIsTheOneThatCounts() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");
		final Account account = this.account("Steve");

		this.f.accounts.updatePassword(account.id(), account.version(), this.f.hasher.hash("changed-by-admin").hash(), HashAlgo.BCRYPT);

		assertOutcome(Outcome.WRONG_PASSWORD, get(this.f.service.login(session, PASSWORD)));
		assertOutcome(Outcome.OK, get(this.f.service.login(session, "changed-by-admin")));
	}

	@Test
	void anAccountRemovedBehindTheBackCannotLogIn() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		this.f.accounts.delete(this.account("Steve").id());

		assertOutcome(Outcome.NOT_REGISTERED, get(this.f.service.login(session, PASSWORD)));
		assertEquals(AuthState.PENDING_LOGIN, session.state());
	}

	@Test
	void anAccountWithoutAPasswordCannotLogInWithOne() throws Exception {
		final UUID uuid = UUID.randomUUID();

		// An original account that somehow ended up on an offline connection (the connection itself is refused, but the command must be safe too).
		this.f.accounts.insert(new NewAccount(uuid, "Notch", true, NOTCH, null, null, null, null));

		final PlayerSession session = new PlayerSession(uuid, "Notch", this.f.ip, this.f.clock.instant(), this.f.clock.instant().plusSeconds(60), false, AuthState.PENDING_LOGIN, null,
				this.f.accounts.findByUsername("Notch").orElseThrow());

		assertOutcome(Outcome.PREMIUM_ACCOUNT, get(this.f.service.login(session, PASSWORD)));
	}

	// ---- old hashes from mineLogin ----

	@Test
	void anAccountImportedFromMineLoginLogsInAndItsHashIsUpgraded() throws Exception {
		final UUID uuid = UUID.randomUUID();

		this.f.accounts.insert(new NewAccount(uuid, "Imported", false, null, SHA256_SALTED, SALT, HashAlgo.SHA256, null));

		final PlayerSession session = this.f.service.open("Imported", uuid, this.f.ip, false).session();

		assertOutcome(Outcome.WRONG_PASSWORD, get(this.f.service.login(session, "hunter3")));
		assertEquals(HashAlgo.SHA256, this.account("Imported").hashAlgo(), "a failed login must not change the hash");
		assertOutcome(Outcome.OK, get(this.f.service.login(session, "hunter2")));

		assertTrue(waitFor(() -> {
			try {
				return this.account("Imported").hashAlgo() == HashAlgo.BCRYPT;
			} catch (final Exception ex) {
				return false;
			}
		}, 10_000), "the old hash was not replaced");

		final Account upgraded = this.account("Imported");

		assertNull(upgraded.passwordSalt(), "a new hash never keeps the old salt");
		assertTrue(this.f.hasher.verify(upgraded.hashAlgo(), upgraded.passwordHash(), upgraded.passwordSalt(), "hunter2"));
		assertEquals(1, this.f.count("SELECT COUNT(*) FROM auth_audit WHERE event = 'PASSWORD_UPGRADED' AND details = 'SHA256 -> BCRYPT'"));

		// And the player can log in with the same password afterwards, now through the new hash.
		this.f.service.close(session);
		assertOutcome(Outcome.OK, get(this.f.service.login(this.f.service.open("Imported", uuid, this.f.otherIp, false).session(), "hunter2")));
	}

	@Test
	void aCurrentHashIsNotRewrittenAtEveryLogin() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");
		final String before = this.account("Steve").passwordHash();

		get(this.f.service.login(session, PASSWORD));
		Thread.sleep(300);

		assertEquals(before, this.account("Steve").passwordHash());
		assertEquals(0, this.audit("PASSWORD_UPGRADED"));
	}

	// ---- change password ----

	@Test
	void changingThePasswordNeedsTheOldOne() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertOutcome(Outcome.WRONG_PASSWORD, get(this.f.service.changePassword(session, "not-it", "brand-new-pass")));
		assertOutcome(Outcome.OK, get(this.f.service.changePassword(session, PASSWORD, "brand-new-pass")));

		final Account account = this.account("Steve");

		assertTrue(this.f.hasher.verify(account.hashAlgo(), account.passwordHash(), null, "brand-new-pass"));
		assertFalse(this.f.hasher.verify(account.hashAlgo(), account.passwordHash(), null, PASSWORD));
		assertEquals(1, this.audit("PASSWORD_CHANGED"));
		assertEquals(account.version(), session.account().version(), "the session sees the new account");
	}

	@Test
	void changingThePasswordEndsTheSessionOfOtherAddresses() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertTrue(this.f.ipSessions.findValid(session.account().id(), this.f.ip, this.f.clock.instant()).isPresent());

		get(this.f.service.changePassword(session, PASSWORD, "brand-new-pass"));

		assertTrue(this.f.ipSessions.findValid(session.account().id(), this.f.ip, this.f.clock.instant()).isEmpty());
	}

	@Test
	void changingThePasswordRefusesWeakOnesAndWrongStatesAndPremiumAccounts() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertOutcome(Outcome.WEAK_PASSWORD, get(this.f.service.changePassword(session, PASSWORD, "short")));
		assertOutcome(Outcome.NOT_LOGGED_IN, get(this.f.service.changePassword(this.f.returningPlayer("Alex"), PASSWORD, "brand-new-pass")));
		assertOutcome(Outcome.PREMIUM_ACCOUNT, get(this.f.service.changePassword(this.f.premiumPlayer("Notch", NOTCH), PASSWORD, "brand-new-pass")));
	}

	@Test
	void wrongPasswordsWhileChangingItCountTowardsTheAddressLimit() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");
		Result last = null;

		for (int i = 0; i < 8; i++)
			last = get(this.f.service.changePassword(session, "wrong-" + i, "brand-new-pass"));

		assertOutcome(Outcome.IP_BLOCKED, last);
	}

	// ---- unregister ----

	@Test
	void unregisteringRemovesTheAccountAndNeedsThePassword() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertOutcome(Outcome.WRONG_PASSWORD, get(this.f.service.unregister(session, "not-it")));
		assertEquals(1, this.f.accounts.count());

		assertOutcome(Outcome.OK_RECONNECT, get(this.f.service.unregister(session, PASSWORD)));

		assertEquals(0, this.f.accounts.count());
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_sessions"));
		assertEquals(1, this.audit("UNREGISTER"));
	}

	@Test
	void unregisteringIsRefusedUnlessLoggedInAndNotForOriginalAccounts() throws Exception {
		assertOutcome(Outcome.NOT_LOGGED_IN, get(this.f.service.unregister(this.f.returningPlayer("Steve"), PASSWORD)));
		assertOutcome(Outcome.PREMIUM_ACCOUNT, get(this.f.service.unregister(this.f.premiumPlayer("Notch", NOTCH), PASSWORD)));
	}

	// ---- premium and cracked ----

	@Test
	void switchingToPremiumNeedsTwoCallsAndMojangsWord() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Notch");
		final Account before = this.account("Notch");

		assertOutcome(Outcome.CONFIRM_REQUIRED, get(this.f.service.premium(session)));
		assertFalse(this.account("Notch").premium(), "the first call must change nothing");

		assertOutcome(Outcome.OK_RECONNECT, get(this.f.service.premium(session)));

		final Account after = this.account("Notch");

		assertTrue(after.premium());
		assertEquals(NOTCH, after.premiumUuid());
		assertEquals(before.uuid(), after.uuid(), "the in-game UUID must not change");
		assertEquals(before.version() + 1, after.version());
		assertEquals(1, this.audit("PREMIUM_ENABLED"));
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_sessions"), "a password session must not outlive the switch");
	}

	@Test
	void switchingToPremiumIsRefusedForANameMojangDoesNotKnow() throws Exception {
		this.f.api.answers.put("Steve", Answer.notFound());

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		get(this.f.service.premium(session));

		assertOutcome(Outcome.NOT_ORIGINAL_NAME, get(this.f.service.premium(session)));
		assertFalse(this.account("Steve").premium());
	}

	@Test
	void switchingToPremiumIsRefusedWhenNoServiceAnswers() throws Exception {
		this.f.api.answers.clear();

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		get(this.f.service.premium(session));

		assertOutcome(Outcome.LOOKUP_FAILED, get(this.f.service.premium(session)));
		assertFalse(this.account("Steve").premium());
	}

	@Test
	void theConfirmationOfPremiumExpires() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Notch");

		get(this.f.service.premium(session));
		this.f.clock.advance(Duration.ofSeconds(31));

		assertOutcome(Outcome.CONFIRM_REQUIRED, get(this.f.service.premium(session)), "too late: this is a new first call");
		assertFalse(this.account("Notch").premium());
	}

	@Test
	void switchingToPremiumIsRefusedUnlessLoggedInAndNotForAnOriginalAccount() throws Exception {
		assertOutcome(Outcome.NOT_LOGGED_IN, get(this.f.service.premium(this.f.returningPlayer("Steve"))));
		assertOutcome(Outcome.PREMIUM_ACCOUNT, get(this.f.service.premium(this.f.premiumPlayer("Notch", NOTCH))));
	}

	@Test
	void aPremiumAccountCanBecomeNonPremiumWithAPassword() throws Exception {
		final PlayerSession session = this.f.premiumPlayer("Notch", NOTCH);

		assertOutcome(Outcome.CONFIRM_REQUIRED, get(this.f.service.cracked(session, "my-new-password", "my-new-password")));
		assertTrue(this.account("Notch").premium(), "the first call must change nothing");

		assertOutcome(Outcome.OK_RECONNECT, get(this.f.service.cracked(session, "my-new-password", "my-new-password")));

		final Account account = this.account("Notch");

		assertFalse(account.premium());
		assertNull(account.premiumUuid());
		assertEquals(NOTCH, account.uuid());
		assertTrue(this.f.hasher.verify(account.hashAlgo(), account.passwordHash(), null, "my-new-password"));
		assertEquals(1, this.audit("CRACKED_ENABLED"));
	}

	@Test
	void becomingNonPremiumRefusesMismatchedWeakAndWrongAccounts() throws Exception {
		final PlayerSession premium = this.f.premiumPlayer("Notch", NOTCH);

		assertOutcome(Outcome.PASSWORD_MISMATCH, get(this.f.service.cracked(premium, "my-new-password", "different")));
		assertOutcome(Outcome.WEAK_PASSWORD, get(this.f.service.cracked(premium, "short", "short")));
		assertOutcome(Outcome.NOT_PREMIUM_ACCOUNT, get(this.f.service.cracked(this.f.loggedInPlayer("Steve"), "my-new-password", "my-new-password")));
		assertOutcome(Outcome.NOT_LOGGED_IN, get(this.f.service.cracked(this.f.returningPlayer("Alex"), "my-new-password", "my-new-password")));
		assertTrue(this.account("Notch").premium());
	}

	@Test
	void aConfirmationForPremiumCannotBeUsedForCracked() throws Exception {
		final PlayerSession session = this.f.premiumPlayer("Notch", NOTCH);

		session.confirm("premium", this.f.clock.instant(), Duration.ofSeconds(30));

		assertOutcome(Outcome.CONFIRM_REQUIRED, get(this.f.service.cracked(session, "my-new-password", "my-new-password")));
		assertTrue(this.account("Notch").premium());
	}

	// ---- concurrency and load ----

	@Test
	void twoCommandsOfOnePlayerAtOnceDoNotBothRun() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");
		final CountDownLatch go = new CountDownLatch(1);
		final List<CompletableFuture<Result>> results = new ArrayList<>();

		for (int i = 0; i < 6; i++)
			results.add(CompletableFuture.supplyAsync(() -> {
				try {
					go.await();
				} catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
				}

				try {
					return get(this.f.service.register(session, PASSWORD, PASSWORD));
				} catch (final Exception ex) {
					throw new IllegalStateException(ex);
				}
			}));

		go.countDown();

		int ok = 0;

		for (final CompletableFuture<Result> result : results)
			if (result.get(20, TimeUnit.SECONDS).outcome() == Outcome.OK)
				ok++;

		assertEquals(1, ok, "exactly one registration may succeed");
		assertEquals(1, this.f.accounts.count());
	}

	@Test
	void whenHashingIsSaturatedThePlayerIsToldToTryAgainInsteadOfWaiting() throws Exception {
		try (AuthFixture small = new AuthFixture(true, 1, 1)) {
			final PlayerSession session = small.returningPlayer("Steve");
			final CountDownLatch release = new CountDownLatch(1);

			// One hash running and one waiting fill the pool. Wait until the first is really running, or the second could find the queue still holding it.
			final List<CompletableFuture<Integer>> blockers = new ArrayList<>();
			final CountDownLatch running = new CountDownLatch(1);

			for (int i = 0; i < 2; i++) {
				blockers.add(small.hashPool.submit(() -> {
					running.countDown();

					try {
						release.await();
					} catch (final InterruptedException ex) {
						Thread.currentThread().interrupt();
					}

					return 1;
				}));

				if (i == 0)
					assertTrue(running.await(10, TimeUnit.SECONDS), "the first blocker never started");
			}

			assertFalse(blockers.get(1).isCompletedExceptionally(), "the second blocker must have fitted in the queue");
			assertEquals(1, small.hashPool.queued());

			final long start = System.nanoTime();

			assertOutcome(Outcome.BUSY, get(small.service.login(session, PASSWORD)));
			assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "must be refused at once, not wait for a thread");
			assertEquals(AuthState.PENDING_LOGIN, session.state());

			release.countDown();

			for (final CompletableFuture<Integer> blocker : blockers)
				blocker.get(10, TimeUnit.SECONDS);

			assertOutcome(Outcome.OK, get(small.service.login(session, PASSWORD)), "the player can try again once there is room");
		}
	}

	@Test
	void aFailedCommandFreesTheSlotSoThePlayerCanTryAgain() throws Exception {
		final PlayerSession session = this.f.newPlayer("Steve");

		this.f.accounts.insert(new NewAccount(UUID.randomUUID(), "Steve", false, null, "$2a$04$h", null, HashAlgo.BCRYPT, null));

		assertOutcome(Outcome.NAME_TAKEN, get(this.f.service.register(session, PASSWORD, PASSWORD)));
		assertOutcome(Outcome.NAME_TAKEN, get(this.f.service.register(session, PASSWORD, PASSWORD)));
		assertNotEquals(Outcome.BUSY, get(this.f.service.register(session, PASSWORD, PASSWORD)).outcome());
	}

	// ---- changes behind the proxy's back ----

	@Test
	void anAdminCanLogAPlayerInAndOut() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		this.f.service.forceAuthenticated(session);

		assertEquals(AuthState.AUTHENTICATED, session.state());
		assertEquals(AuthMethod.FORCED, session.method());
		assertEquals(AuthMethod.FORCED, this.f.presence.find(session.uuid()).orElseThrow().method());

		this.f.service.forceLoggedOut(session);

		assertEquals(AuthState.PENDING_LOGIN, session.state());
		assertEquals(AuthState.PENDING_LOGIN, this.f.presence.find(session.uuid()).orElseThrow().state());
	}

	@Test
	void forcingALogoutOfAPlayerWhoseAccountIsGoneStartsRegistration() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.f.accounts.delete(this.account("Steve").id());
		this.f.service.forceLoggedOut(session);

		assertEquals(AuthState.PENDING_REGISTER, session.state());
		assertNull(session.account());
	}

	@Test
	void refreshReloadsTheAccountOrReportsItGone() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");
		final Account account = this.account("Steve");

		this.f.accounts.updatePassword(account.id(), account.version(), "x", HashAlgo.BCRYPT);

		assertEquals(account.version() + 1, this.f.service.refresh(session).orElseThrow().version());
		assertEquals(account.version() + 1, session.account().version());

		this.f.accounts.delete(account.id());

		assertTrue(this.f.service.refresh(session).isEmpty());
	}
}
