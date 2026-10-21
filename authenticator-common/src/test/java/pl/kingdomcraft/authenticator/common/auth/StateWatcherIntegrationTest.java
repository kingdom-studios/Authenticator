package pl.kingdomcraft.authenticator.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.NOTCH;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository.Presence;
import pl.kingdomcraft.authenticator.common.support.AuthFixture;

/** What the proxy does about changes an admin made on a backend server, which can only reach it through the database. */
class StateWatcherIntegrationTest {

	private AuthFixture f;
	private StateWatcher watcher;
	private final List<String> events = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		this.f = new AuthFixture();
		this.watcher = new StateWatcher(this.f.service, this.f.accounts, this.f.presence, this.f.sessions, "velocity-1", new StateWatcher.Handler() {
			@Override
			public void accountRemoved(PlayerSession session) {
				StateWatcherIntegrationTest.this.events.add("removed:" + session.username());
			}

			@Override
			public void typeChanged(PlayerSession session) {
				StateWatcherIntegrationTest.this.events.add("type:" + session.username());
			}

			@Override
			public void stateForced(PlayerSession session) {
				StateWatcherIntegrationTest.this.events.add("forced:" + session.username() + ":" + session.state());
			}
		}, AuthFixture.LOGGER);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.f != null)
			this.f.close();
	}

	private Presence row(PlayerSession session) throws Exception {
		return this.f.presence.find(session.uuid()).orElseThrow();
	}

	/** What a backend server's /authadmin does: write a row under its own node id. */
	private void adminWrites(PlayerSession session, AuthState state, AuthMethod method) throws Exception {
		this.f.presence.save(session.uuid(), session.account() == null ? null : session.account().id(), state, method, "paper-auth-1");
	}

	@Test
	void nothingHappensWhenNothingChanged() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");
		final long epoch = session.epoch();

		this.watcher.checkAll();
		this.watcher.checkAll();

		assertEquals(List.of(), this.events);
		assertEquals(epoch, session.epoch(), "no write, no new epoch");
		assertEquals(epoch, this.row(session).epoch());
		assertTrue(session.authenticated());
	}

	@Test
	void aRemovedAccountSendsThePlayerBackToRegistering() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.f.accounts.delete(session.account().id());
		this.watcher.checkAll();

		assertEquals(List.of("removed:Steve"), this.events);
		assertEquals(AuthState.PENDING_REGISTER, session.state());
		assertNull(session.account());
		assertEquals(AuthState.PENDING_REGISTER, this.row(session).state(), "the database says so too, for the servers");
		assertEquals("velocity-1", this.row(session).proxyId());

		this.watcher.checkAll();
		assertEquals(1, this.events.size(), "reported once");
	}

	@Test
	void aPlayerWhoseAccountWasRemovedCanRegisterAgain() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.f.accounts.delete(session.account().id());
		this.watcher.checkAll();

		assertTrue(this.f.service.register(session, "another-password", "another-password").get().ok());
		assertTrue(session.authenticated());
		assertEquals(session.uuid(), this.f.accounts.findByUsername("Steve").orElseThrow().uuid());
	}

	@Test
	void anAdminChangingThePasswordIsNoticedButKeepsThePlayerLoggedIn() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");
		final Account before = session.account();

		assertTrue(this.f.accounts.updatePassword(before.id(), before.version(), this.f.hasher.hash("brand-new-secret").hash(), HashAlgo.BCRYPT));
		this.watcher.checkAll();

		assertEquals(List.of(), this.events, "a new password needs nobody to be kicked");
		assertTrue(session.authenticated());
		assertEquals(before.version() + 1, session.account().version(), "the session now knows the new account");
		assertEquals(1L, this.watcher.counters().get("accounts-changed"));

		this.watcher.checkAll();
		assertEquals(1L, this.watcher.counters().get("accounts-changed"), "and only once");
	}

	@Test
	void anAdminChangingTheAccountTypeMakesThePlayerReconnect() throws Exception {
		this.f.api.answers.put("Steve", pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer.found(NOTCH));

		final PlayerSession session = this.f.loggedInPlayer("Steve");
		final Account before = session.account();

		assertTrue(this.f.accounts.setPremium(before.id(), before.version(), NOTCH));
		this.watcher.checkAll();

		assertEquals(List.of("type:Steve"), this.events);
		assertTrue(session.account().premium());
	}

	@Test
	void aForcedLoginFromAnotherNodeLogsThePlayerIn() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		assertEquals(AuthState.PENDING_LOGIN, session.state());

		this.adminWrites(session, AuthState.AUTHENTICATED, AuthMethod.FORCED);
		this.watcher.checkAll();

		assertEquals(List.of("forced:Steve:AUTHENTICATED"), this.events);
		assertTrue(session.authenticated());
		assertEquals(AuthMethod.FORCED, session.method());
		assertEquals("velocity-1", this.row(session).proxyId(), "the proxy took over the row, so the servers hear it from the proxy");
		assertEquals(session.epoch(), this.row(session).epoch());
		assertTrue(this.f.notifications.stream().anyMatch(n -> n.startsWith("Steve:AUTHENTICATED:FORCED")), this.f.notifications.toString());

		this.watcher.checkAll();
		assertEquals(1, this.events.size());
	}

	@Test
	void aLogoutFromAnotherNodeLogsThePlayerOut() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.adminWrites(session, AuthState.PENDING_LOGIN, null);
		this.watcher.checkAll();

		assertEquals(List.of("forced:Steve:PENDING_LOGIN"), this.events);
		assertEquals(AuthState.PENDING_LOGIN, session.state());
		assertFalse(session.authenticated());
	}

	@Test
	void aLoginStateOtherThanForcedFromAnotherNodeIsIgnoredAndPutRight() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		this.adminWrites(session, AuthState.AUTHENTICATED, AuthMethod.PASSWORD);
		this.watcher.checkAll();

		assertEquals(List.of(), this.events);
		assertFalse(session.authenticated(), "only the proxy can say a password was typed");
		assertEquals(AuthState.PENDING_LOGIN, this.row(session).state(), "the proxy's own state is back in the database");
		assertEquals(1L, this.watcher.counters().get("foreign-states-ignored"));

		this.watcher.checkAll();
		assertEquals(1L, this.watcher.counters().get("foreign-states-ignored"), "and the correction is not judged again");
	}

	@Test
	void aForeignRowOlderThanTheProxysOwnLastWriteIsStale() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		this.adminWrites(session, AuthState.AUTHENTICATED, AuthMethod.FORCED); // epoch 3 (the join wrote 1, a login 2 was never needed, so this is just after the join)
		session.epoch(this.row(session).epoch() + 10); // the proxy has written many times since, e.g. the database was restored from an older backup

		this.watcher.checkAll();

		assertEquals(List.of(), this.events);
		assertFalse(session.authenticated(), "a row older than what the proxy last wrote is no command");
	}

	@Test
	void theProxysOwnRowsAreNeverTakenForACommand() throws Exception {
		final PlayerSession session = this.f.returningPlayer("Steve");

		// The row is newer than the session's epoch for a moment while the proxy is still writing it.
		this.f.presence.save(session.uuid(), session.account().id(), AuthState.AUTHENTICATED, AuthMethod.FORCED, "velocity-1");
		this.watcher.checkAll();

		assertEquals(List.of(), this.events);
		assertFalse(session.authenticated());
	}

	@Test
	void anAlreadyLoggedInPlayerIsNotLoggedInTwiceByAForcedLogin() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.adminWrites(session, AuthState.AUTHENTICATED, AuthMethod.FORCED);
		this.watcher.checkAll();

		assertEquals(List.of(), this.events);
		assertEquals(AuthMethod.PASSWORD, session.method(), "how they really got in is kept");
	}

	@Test
	void aDeletedRowIsPutBack() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.f.presence.delete(session.uuid());
		this.watcher.checkAll();

		final Presence row = this.row(session);

		assertEquals(AuthState.AUTHENTICATED, row.state());
		assertEquals(AuthMethod.PASSWORD, row.method());
		assertEquals(1L, this.watcher.counters().get("rows-restored"));
		assertEquals(List.of(), this.events);
	}

	@Test
	void aPlayerWhoLeftWhileTheDatabaseWasReadIsNotTouched() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.f.accounts.delete(session.account().id());
		this.f.sessions.remove(session); // gone before the watcher gets to look

		this.watcher.checkAll();
		this.watcher.check(session.uuid());

		assertEquals(List.of(), this.events);
		assertTrue(this.f.presence.find(session.uuid()).isEmpty(), "and no row is written for someone who is not here");
	}

	@Test
	void aPlayerWhoReconnectedIsJudgedByTheNewSessionOnly() throws Exception {
		final PlayerSession old = this.f.loggedInPlayer("Steve");

		this.f.service.close(old);
		final PlayerSession fresh = this.f.service.open("Steve", old.uuid(), this.f.ip, false).session();

		this.watcher.check(old.uuid());

		assertEquals(List.of(), this.events);
		assertEquals(AuthState.AUTHENTICATED, fresh.state(), "a valid IP session logged the new connection in");
	}

	@Test
	void checkingOnePlayerLeavesTheOthersAlone() throws Exception {
		final PlayerSession steve = this.f.loggedInPlayer("Steve");
		final PlayerSession alex = this.f.loggedInPlayer("Alex");

		this.f.accounts.delete(steve.account().id());
		this.f.accounts.delete(alex.account().id());

		this.watcher.check(steve.uuid());

		assertEquals(List.of("removed:Steve"), this.events);
		assertTrue(alex.authenticated(), "Alex is judged at the next round, or when told about");

		this.watcher.checkAll();

		assertEquals(List.of("removed:Steve", "removed:Alex"), this.events);
	}

	@Test
	void aDatabaseOutageDoesNothingAndTheNextRoundTriesAgain() throws Exception {
		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.f.accounts.delete(session.account().id());
		this.f.execute("RENAME TABLE auth_presence TO auth_presence_away");

		try {
			this.watcher.checkAll();

			assertEquals(List.of(), this.events);
			assertTrue(session.authenticated());

		} finally {
			this.f.execute("RENAME TABLE auth_presence_away TO auth_presence");
		}

		this.watcher.checkAll();

		assertEquals(List.of("removed:Steve"), this.events);
	}

	@Test
	void anEmptyProxyCostsNoQueries() {
		this.watcher.checkAll();

		assertEquals(List.of(), this.events);
	}

	@Test
	void aPremiumPlayerKeepsHisStateWhenOnlyTheLoginTimeChanges() throws Exception {
		final PlayerSession session = this.f.premiumPlayer("Notch", NOTCH);
		final long version = session.account().version();

		this.f.accounts.recordLogin(session.account().id(), this.f.ip, this.f.clock.instant());
		this.watcher.checkAll();

		assertEquals(version, this.f.accounts.findById(session.account().id()).orElseThrow().version(), "recording a login is not a change of the account");
		assertEquals(List.of(), this.events);
	}
}
