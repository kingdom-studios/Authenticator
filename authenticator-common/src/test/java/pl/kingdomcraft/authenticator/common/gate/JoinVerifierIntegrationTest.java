package pl.kingdomcraft.authenticator.common.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.waitFor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.gate.JoinVerifier.Reason;
import pl.kingdomcraft.authenticator.common.gate.JoinVerifier.Role;
import pl.kingdomcraft.authenticator.common.support.AuthFixture;

/** The door of a backend server, on a real database that the "proxy" (the AuthService) writes. */
class JoinVerifierIntegrationTest {

	private AuthFixture f;
	private Gate gate;
	private final List<String> events = new CopyOnWriteArrayList<>();
	private JoinVerifier auth;
	private JoinVerifier game;

	@BeforeEach
	void setUp() throws Exception {
		this.f = new AuthFixture();
		this.gate = new Gate();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.auth != null)
			this.auth.close();

		if (this.game != null)
			this.game.close();

		if (this.f != null)
			this.f.close();
	}

	private JoinVerifier verifier(Role role) {
		return new JoinVerifier(this.f.presence, this.gate, role, Duration.ofMillis(200), new JoinVerifier.Listener() {
			@Override
			public void authenticated(UUID player) {
				JoinVerifierIntegrationTest.this.events.add("in:" + player);
			}

			@Override
			public void deauthenticated(UUID player) {
				JoinVerifierIntegrationTest.this.events.add("out:" + player);
			}
		}, AuthFixture.LOGGER);
	}

	// ---- the door ----

	@Test
	void aPlayerTheProxyKnowsNothingOfIsRefusedByEveryRole() throws Exception {
		this.auth = this.verifier(Role.AUTH);
		this.game = this.verifier(Role.GAME);

		final UUID stranger = UUID.randomUUID();

		assertEquals(Reason.UNKNOWN_TO_PROXY, this.auth.checkLogin(stranger).reason());
		assertEquals(Reason.UNKNOWN_TO_PROXY, this.game.checkLogin(stranger).reason());
		assertFalse(this.gate.authenticated(stranger));
	}

	@Test
	void theWaitingServerTakesAPlayerWhoMustLogInAndFreezesThem() throws Exception {
		this.auth = this.verifier(Role.AUTH);

		final PlayerSession session = this.f.newPlayer("Steve");

		final var decision = this.auth.checkLogin(session.uuid());

		assertTrue(decision.allowed());
		assertEquals(AuthState.PENDING_REGISTER, this.gate.get(session.uuid()).orElseThrow().state());
		assertFalse(this.gate.authenticated(session.uuid()));
	}

	@Test
	void aGameServerRefusesAPlayerWhoHasNotLoggedIn() throws Exception {
		this.game = this.verifier(Role.GAME);

		final PlayerSession session = this.f.returningPlayer("Steve");

		final var decision = this.game.checkLogin(session.uuid());

		assertFalse(decision.allowed());
		assertEquals(Reason.NOT_AUTHENTICATED, decision.reason());
		assertTrue(this.gate.get(session.uuid()).isEmpty(), "and nothing was recorded for the refused player");
	}

	@Test
	void aGameServerTakesAPlayerWhoHasLoggedInAndDoesNotFreezeThem() throws Exception {
		this.game = this.verifier(Role.GAME);

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertTrue(this.game.checkLogin(session.uuid()).allowed());
		assertTrue(this.gate.authenticated(session.uuid()));
		assertEquals(AuthMethod.PASSWORD, this.gate.get(session.uuid()).orElseThrow().method());
	}

	@Test
	void anOriginalPlayerIsLoggedInAtTheDoorOfAnyServer() throws Exception {
		this.auth = this.verifier(Role.AUTH);

		final PlayerSession session = this.f.premiumPlayer("Notch", AuthFixture.NOTCH);

		assertTrue(this.auth.checkLogin(session.uuid()).allowed());
		assertTrue(this.gate.authenticated(session.uuid()));
	}

	@Test
	void whatWasKnownOfAnEarlierConnectionIsForgottenAtTheDoor() throws Exception {
		this.auth = this.verifier(Role.AUTH);
		this.game = this.verifier(Role.GAME);

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		assertTrue(this.game.checkLogin(session.uuid()).allowed());
		assertTrue(this.gate.authenticated(session.uuid()));

		this.f.service.forceLoggedOut(session);

		assertFalse(this.game.checkLogin(session.uuid()).allowed(), "the old yes does not carry over");
		assertFalse(this.gate.authenticated(session.uuid()));
	}

	@Test
	void aDatabaseThatCannotBeReadRefusesEveryone() throws Exception {
		this.game = this.verifier(Role.GAME);
		this.auth = this.verifier(Role.AUTH);

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.f.execute("RENAME TABLE auth_presence TO auth_presence_away");

		try {
			assertEquals(Reason.DATABASE_ERROR, this.game.checkLogin(session.uuid()).reason());
			assertEquals(Reason.DATABASE_ERROR, this.auth.checkLogin(session.uuid()).reason(), "even the waiting server: nothing can be said about the player");

		} finally {
			this.f.execute("RENAME TABLE auth_presence_away TO auth_presence");
		}
	}

	// ---- while the player is here ----

	@Test
	void theDatabaseLogsAPlayerInWithoutAnyMessage() throws Exception {
		this.auth = this.verifier(Role.AUTH);

		final PlayerSession session = this.f.returningPlayer("Steve");

		this.auth.checkLogin(session.uuid());
		this.auth.joined(session.uuid());

		this.f.service.login(session, AuthFixture.PASSWORD).get();

		assertTrue(waitFor(() -> this.gate.authenticated(session.uuid()), 5_000), "the poll found the login");
		assertTrue(waitFor(() -> this.events.contains("in:" + session.uuid()), 5_000), this.events.toString());
	}

	@Test
	void theDatabaseTakesTheLoginBackAsWell() throws Exception {
		this.game = this.verifier(Role.GAME);

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.game.checkLogin(session.uuid());
		this.game.joined(session.uuid());

		this.f.service.forceLoggedOut(session);

		assertTrue(waitFor(() -> !this.gate.authenticated(session.uuid()), 5_000));
		assertTrue(waitFor(() -> this.events.contains("out:" + session.uuid()), 5_000), this.events.toString());
	}

	@Test
	void aMessageFromTheProxyActsAtOnce() throws Exception {
		this.auth = new JoinVerifier(this.f.presence, this.gate, Role.AUTH, Duration.ofSeconds(60), new JoinVerifier.Listener() {
			@Override
			public void authenticated(UUID player) {
				JoinVerifierIntegrationTest.this.events.add("in:" + player);
			}

			@Override
			public void deauthenticated(UUID player) {
				JoinVerifierIntegrationTest.this.events.add("out:" + player);
			}
		}, AuthFixture.LOGGER); // the poll will not run during this test: only the message can do it

		final PlayerSession session = this.f.returningPlayer("Steve");

		this.auth.checkLogin(session.uuid());
		this.auth.joined(session.uuid());
		this.auth.apply(new AuthStateMessage(session.uuid(), AuthState.AUTHENTICATED, AuthMethod.PASSWORD, session.epoch() + 1));

		assertTrue(this.gate.authenticated(session.uuid()));
		assertEquals(List.of("in:" + session.uuid()), this.events);
	}

	@Test
	void aMessageAboutSomeoneNotHereIsNotOurBusiness() throws Exception {
		this.auth = this.verifier(Role.AUTH);

		final UUID elsewhere = UUID.randomUUID();

		this.auth.apply(new AuthStateMessage(elsewhere, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 1));

		assertFalse(this.gate.authenticated(elsewhere));
		assertEquals(0, this.gate.size());
	}

	@Test
	void aLateMessageCannotLogAnybodyInAgain() throws Exception {
		this.auth = this.verifier(Role.AUTH);

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.auth.checkLogin(session.uuid());
		this.auth.joined(session.uuid());
		this.f.service.forceLoggedOut(session);
		this.auth.refreshNow(java.util.Set.of(session.uuid()));

		assertFalse(this.gate.authenticated(session.uuid()));

		// the login that came before the logout turns up late
		this.auth.apply(new AuthStateMessage(session.uuid(), AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 1));

		assertFalse(this.gate.authenticated(session.uuid()));
	}

	@Test
	void aRowThatIsMissingNeitherLogsAnyoneInNorOut() throws Exception {
		this.game = this.verifier(Role.GAME);

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.game.checkLogin(session.uuid());
		this.game.joined(session.uuid());
		this.f.presence.delete(session.uuid()); // the proxy is restarting, say

		this.game.refreshNow(java.util.Set.of(session.uuid()));

		assertTrue(this.gate.authenticated(session.uuid()), "the proxy has said nothing new");
	}

	@Test
	void aPlayerWhoLeftIsNoLongerWatchedOrKnown() throws Exception {
		this.auth = this.verifier(Role.AUTH);

		final PlayerSession session = this.f.returningPlayer("Steve");

		this.auth.checkLogin(session.uuid());
		this.auth.joined(session.uuid());
		this.auth.left(session.uuid());

		this.f.service.login(session, AuthFixture.PASSWORD).get();
		Thread.sleep(700);

		assertTrue(this.gate.get(session.uuid()).isEmpty());
		assertEquals(List.of(), this.events);
	}

	@Test
	void aDatabaseOutageWhilePollingKeepsEveryoneAsTheyWere() throws Exception {
		this.game = this.verifier(Role.GAME);

		final PlayerSession session = this.f.loggedInPlayer("Steve");

		this.game.checkLogin(session.uuid());
		this.game.joined(session.uuid());
		this.f.execute("RENAME TABLE auth_presence TO auth_presence_away");

		try {
			Thread.sleep(600);

			assertTrue(this.gate.authenticated(session.uuid()));

		} finally {
			this.f.execute("RENAME TABLE auth_presence_away TO auth_presence");
		}
	}

	@Test
	void aListenerThatFailsDoesNotStopTheWatching() throws Exception {
		final List<UUID> seen = new CopyOnWriteArrayList<>();

		this.auth = new JoinVerifier(this.f.presence, this.gate, Role.AUTH, Duration.ofMillis(200), new JoinVerifier.Listener() {
			@Override
			public void authenticated(UUID player) {
				seen.add(player);

				throw new IllegalStateException("boom");
			}

			@Override
			public void deauthenticated(UUID player) {
			}
		}, AuthFixture.LOGGER);

		final PlayerSession steve = this.f.returningPlayer("Steve");
		final PlayerSession alex = this.f.returningPlayer("Alex");

		for (final PlayerSession session : List.of(steve, alex)) {
			this.auth.checkLogin(session.uuid());
			this.auth.joined(session.uuid());
		}

		this.f.service.login(steve, AuthFixture.PASSWORD).get();
		this.f.service.login(alex, AuthFixture.PASSWORD).get();

		assertTrue(waitFor(() -> seen.size() == 2, 5_000), seen.toString());
		assertTrue(this.gate.authenticated(steve.uuid()) && this.gate.authenticated(alex.uuid()));
	}
}
