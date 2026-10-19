package pl.kingdomcraft.authenticator.common.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.NOTCH;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.PASSWORD;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.admin.AdminService.Outcome;
import pl.kingdomcraft.authenticator.common.admin.AdminService.Result;
import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.auth.PasswordPolicy;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.support.AuthFixture;

/** What an admin on a backend server can do to an account, on a real database. The "proxy" side is the AuthService of the fixture. */
class AdminServiceIntegrationTest {

	private AuthFixture f;
	private AdminService admin;

	@BeforeEach
	void setUp() throws Exception {
		this.f = new AuthFixture();

		final PremiumResolver resolver = new PremiumResolver(List.of(this.f.api), this.f.premiumCache, PremiumResolver.Timing.defaults(), this.f.clock, AuthFixture.LOGGER);

		this.admin = new AdminService(this.f.accounts, this.f.ipSessions, this.f.presence, this.f.audit, this.f.hasher, new PasswordPolicy(6, List.of("password123")), resolver, "paper-auth-1", AuthFixture.LOGGER);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.f != null)
			this.f.close();
	}

	private Account account(String name) throws Exception {
		return this.f.accounts.findByUsername(name).orElseThrow();
	}

	private int audit(String event) throws Exception {
		return this.f.count("SELECT COUNT(*) FROM auth_audit WHERE event = '" + event + "' AND actor = 'Admin' AND node = 'paper-auth-1'");
	}

	private static void assertOutcome(Outcome expected, Result result) {
		assertEquals(expected, result.outcome(), result.toString());
	}

	// ---- info ----

	@Test
	void infoShowsTheAccountAndWhereThePlayerIs() throws Exception {
		assertTrue(this.admin.info("nobody").isEmpty());

		final PlayerSession session = this.f.loggedInPlayer("Steve");
		final var info = this.admin.info("steve").orElseThrow();

		assertEquals("Steve", info.account().username());
		assertEquals(AuthState.AUTHENTICATED, info.presence().state());

		this.f.service.close(session);

		assertNull(this.admin.info("Steve").orElseThrow().presence(), "not on the network any more");
	}

	// ---- unregister ----

	@Test
	void unregisteringRemovesTheAccountAndTellsWhatToAnnounce() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		final Result result = this.admin.unregister("Steve", "Admin");

		assertOutcome(Outcome.OK, result);
		assertEquals(session.uuid(), result.player());
		assertEquals(AccountChange.REMOVED, result.change());
		assertTrue(this.f.accounts.findByUsername("Steve").isEmpty());
		assertEquals(1, this.audit("UNREGISTER"));
		assertOutcome(Outcome.NO_SUCH_ACCOUNT, this.admin.unregister("Steve", "Admin"));
	}

	// ---- changepassword ----

	@Test
	void aNewPasswordWorksTheOldOneDoesNotAndSessionsAreEnded() throws Exception {
		this.f.loggedInPlayer("Steve");

		final Account before = this.account("Steve");
		final Result result = this.admin.changePassword("Steve", "a-brand-new-one", "Admin");

		assertOutcome(Outcome.OK, result);
		assertEquals(AccountChange.PASSWORD, result.change());
		assertEquals(before.version() + 1, this.account("Steve").version());
		assertTrue(this.f.hasher.verify(this.account("Steve").hashAlgo(), this.account("Steve").passwordHash(), this.account("Steve").passwordSalt(), "a-brand-new-one"));
		assertFalse(this.f.hasher.verify(this.account("Steve").hashAlgo(), this.account("Steve").passwordHash(), this.account("Steve").passwordSalt(), PASSWORD));
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_sessions"), "another address must type the new password");
		assertEquals(1, this.audit("PASSWORD_CHANGED"));
	}

	@Test
	void aWeakOrMisplacedPasswordIsRefusedAndNothingChanges() throws Exception {
		this.f.loggedInPlayer("Steve");
		this.f.premiumPlayer("Notch", NOTCH);

		final String hash = this.account("Steve").passwordHash();

		final Result tooShort = this.admin.changePassword("Steve", "abc", "Admin");

		assertOutcome(Outcome.WEAK_PASSWORD, tooShort);
		assertEquals("TOO_SHORT:6", tooShort.detail());
		assertOutcome(Outcome.WEAK_PASSWORD, this.admin.changePassword("Steve", "password123", "Admin"));
		assertOutcome(Outcome.WEAK_PASSWORD, this.admin.changePassword("Steve", "has space", "Admin"));
		assertOutcome(Outcome.WEAK_PASSWORD, this.admin.changePassword("Steve", "steve", "Admin"));
		assertOutcome(Outcome.PREMIUM_ACCOUNT, this.admin.changePassword("Notch", "a-brand-new-one", "Admin"));
		assertOutcome(Outcome.NO_SUCH_ACCOUNT, this.admin.changePassword("Nobody", "a-brand-new-one", "Admin"));
		assertEquals(hash, this.account("Steve").passwordHash());
		assertEquals(0, this.audit("PASSWORD_CHANGED"));
	}

	// ---- forcelogin / forcelogout ----

	@Test
	void aForcedLoginIsWrittenAsTheDatabaseRowTheProxyWatches() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");
		final long epoch = this.f.presence.find(session.uuid()).orElseThrow().epoch();

		final Result result = this.admin.forceLogin("Steve", "Admin");

		assertOutcome(Outcome.OK, result);
		assertEquals(AccountChange.FORCED_LOGIN, result.change());

		final var row = this.f.presence.find(session.uuid()).orElseThrow();

		assertEquals(AuthState.AUTHENTICATED, row.state());
		assertEquals(AuthMethod.FORCED, row.method());
		assertEquals("paper-auth-1", row.proxyId(), "the node that wrote it, so the proxy can tell it is a command");
		assertEquals(epoch + 1, row.epoch());
		assertEquals(1, this.audit("FORCED_LOGIN"));
	}

	@Test
	void forcingALoginNeedsAnAccountAndAPlayerWhoIsWaiting() throws Exception {
		assertOutcome(Outcome.NO_SUCH_ACCOUNT, this.admin.forceLogin("Nobody", "Admin"));

		final PlayerSession session = this.f.returningPlayer("Steve");
		this.f.service.close(session);

		assertOutcome(Outcome.NOT_ONLINE, this.admin.forceLogin("Steve", "Admin"));
		assertTrue(this.f.presence.find(session.uuid()).isEmpty(), "and no row was invented for a player who is not there");

		final PlayerSession again = this.f.returningPlayer("Alex");
		this.f.service.login(again, PASSWORD).get();

		assertOutcome(Outcome.ALREADY_LOGGED_IN, this.admin.forceLogin("Alex", "Admin"));
	}

	@Test
	void aForcedLogoutMakesTheRowPendingAgain() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		final Result result = this.admin.forceLogout("Steve", "Admin");

		assertOutcome(Outcome.OK, result);
		assertEquals(AccountChange.FORCED_LOGOUT, result.change());

		final var row = this.f.presence.find(session.uuid()).orElseThrow();

		assertEquals(AuthState.PENDING_LOGIN, row.state());
		assertNull(row.method());
		assertEquals("paper-auth-1", row.proxyId());
		assertEquals(1, this.audit("FORCED_LOGOUT"));
	}

	@Test
	void forcingALogoutNeedsAPasswordAccountThatIsLoggedIn() throws Exception {
		this.f.premiumPlayer("Notch", NOTCH);

		assertOutcome(Outcome.PREMIUM_ACCOUNT, this.admin.forceLogout("Notch", "Admin"));
		assertOutcome(Outcome.NO_SUCH_ACCOUNT, this.admin.forceLogout("Nobody", "Admin"));

		this.f.returningPlayer("Steve");

		assertOutcome(Outcome.NOT_LOGGED_IN, this.admin.forceLogout("Steve", "Admin"));

		final PlayerSession alex = this.f.loggedInPlayer("Alex");
		this.f.service.close(alex);

		assertOutcome(Outcome.NOT_ONLINE, this.admin.forceLogout("Alex", "Admin"));
	}

	// ---- setpremium / setcracked ----

	@Test
	void anOriginalNameCanBeMadeAPremiumAccount() throws Exception {
		this.f.loggedInPlayer("Notch");
		this.f.ipSessions.save(this.account("Notch").id(), this.f.ip, this.f.clock.instant().plusSeconds(3600));

		final Result result = this.admin.setPremium("Notch", "Admin");

		assertOutcome(Outcome.OK, result);
		assertEquals(AccountChange.TYPE, result.change());

		final Account after = this.account("Notch");

		assertTrue(after.premium());
		assertEquals(NOTCH, after.premiumUuid());
		assertEquals(0, this.f.count("SELECT COUNT(*) FROM auth_sessions"));
		assertEquals(1, this.audit("PREMIUM_ENABLED"));
	}

	@Test
	void aNameMojangDoesNotKnowOrCannotBeAskedAboutStaysAsItIs() throws Exception {
		this.f.api.answers.put("Steve", Answer.notFound());
		this.f.loggedInPlayer("Steve");

		assertOutcome(Outcome.NOT_ORIGINAL_NAME, this.admin.setPremium("Steve", "Admin"));
		assertFalse(this.account("Steve").premium());

		this.f.api.answers.clear();
		this.f.loggedInPlayer("Alex");

		assertOutcome(Outcome.LOOKUP_FAILED, this.admin.setPremium("Alex", "Admin"));
		assertFalse(this.account("Alex").premium());
		assertEquals(0, this.audit("PREMIUM_ENABLED"));
	}

	@Test
	void theMojangAccountOfAnotherAccountIsAConflictNotACrash() throws Exception {
		this.f.premiumPlayer("Notch", NOTCH);
		this.f.api.answers.put("Steve", Answer.found(NOTCH)); // two names for one Mojang account, as after a rename
		this.f.loggedInPlayer("Steve");

		assertOutcome(Outcome.CONFLICT, this.admin.setPremium("Steve", "Admin"));
		assertFalse(this.account("Steve").premium());
	}

	@Test
	void setPremiumIsForPasswordAccountsOnly() throws Exception {
		this.f.premiumPlayer("Notch", NOTCH);

		assertOutcome(Outcome.PREMIUM_ACCOUNT, this.admin.setPremium("Notch", "Admin"));
		assertOutcome(Outcome.NO_SUCH_ACCOUNT, this.admin.setPremium("Nobody", "Admin"));
	}

	@Test
	void anOriginalAccountCanBecomeAPasswordAccount() throws Exception {
		this.f.premiumPlayer("Notch", NOTCH);

		final Result result = this.admin.setCracked("Notch", "a-brand-new-one", "Admin");

		assertOutcome(Outcome.OK, result);
		assertEquals(AccountChange.TYPE, result.change());

		final Account after = this.account("Notch");

		assertFalse(after.premium());
		assertNull(after.premiumUuid());
		assertTrue(this.f.hasher.verify(after.hashAlgo(), after.passwordHash(), after.passwordSalt(), "a-brand-new-one"));
		assertEquals(1, this.audit("CRACKED_ENABLED"));
	}

	@Test
	void setCrackedRefusesWeakPasswordsAndPasswordAccounts() throws Exception {
		this.f.premiumPlayer("Notch", NOTCH);
		this.f.loggedInPlayer("Steve");

		assertOutcome(Outcome.WEAK_PASSWORD, this.admin.setCracked("Notch", "abc", "Admin"));
		assertTrue(this.account("Notch").premium());
		assertOutcome(Outcome.NOT_PREMIUM_ACCOUNT, this.admin.setCracked("Steve", "a-brand-new-one", "Admin"));
		assertOutcome(Outcome.NO_SUCH_ACCOUNT, this.admin.setCracked("Nobody", "a-brand-new-one", "Admin"));
	}

	// ---- the database going wrong ----

	@Test
	void aDatabaseFailureIsAnErrorNotAnExceptionAndChangesNothing() throws Exception {
		this.f.loggedInPlayer("Steve");
		this.f.execute("RENAME TABLE auth_accounts TO auth_accounts_away");

		try {
			assertOutcome(Outcome.ERROR, this.admin.changePassword("Steve", "a-brand-new-one", "Admin"));
			assertOutcome(Outcome.ERROR, this.admin.unregister("Steve", "Admin"));

		} finally {
			this.f.execute("RENAME TABLE auth_accounts_away TO auth_accounts");
		}

		assertNotEquals(null, this.account("Steve"));
	}

	@Test
	void theUuidOfTheResultIsTheOneOnTheServers() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");
		final Result result = this.admin.changePassword("Steve", "a-brand-new-one", "Admin");

		assertEquals(session.uuid(), result.player());
		assertNotEquals(new UUID(0, 0), result.player());
	}
}
