package pl.kingdomcraft.authenticator.common.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlayerSessionTest {

	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private static PlayerSession session(String name, AuthState state, Instant deadline) throws Exception {
		return new PlayerSession(UUID.randomUUID(), name, InetAddress.getByName("203.0.113.7"), NOW, deadline, false, state, null, null);
	}

	@Test
	void anAuthenticatedPlayerNeverExpiresButAWaitingOneDoesAtTheDeadline() throws Exception {
		final PlayerSession waiting = session("A", AuthState.PENDING_LOGIN, NOW.plusSeconds(60));

		assertFalse(waiting.expired(NOW.plusSeconds(59)));
		assertTrue(waiting.expired(NOW.plusSeconds(60)));

		waiting.authenticate(AuthMethod.PASSWORD, null);

		assertFalse(waiting.expired(NOW.plusSeconds(3600)));
		assertTrue(waiting.authenticated());
		assertEquals(AuthMethod.PASSWORD, waiting.method());
	}

	@Test
	void authenticatingClearsTheFailuresAndAResetStartsOver() throws Exception {
		final PlayerSession session = session("A", AuthState.PENDING_LOGIN, NOW.plusSeconds(60));

		assertEquals(1, session.recordFailure());
		assertEquals(2, session.recordFailure());

		session.authenticate(AuthMethod.PASSWORD, null);
		assertEquals(0, session.failedAttempts());

		session.reset(AuthState.PENDING_LOGIN, null);
		assertEquals(AuthState.PENDING_LOGIN, session.state());
		assertNull(session.method());
		assertFalse(session.authenticated());
	}

	@Test
	void aDestructiveCommandNeedsTwoCallsOfTheSameKindInTime() throws Exception {
		final PlayerSession session = session("A", AuthState.AUTHENTICATED, NOW.plusSeconds(60));
		final Duration window = Duration.ofSeconds(30);

		assertFalse(session.confirm("premium", NOW, window), "the first call only arms it");
		assertTrue(session.confirm("premium", NOW.plusSeconds(10), window));
		assertFalse(session.confirm("premium", NOW.plusSeconds(11), window), "a confirmation is used up");
	}

	@Test
	void aConfirmationExpiresAndIsNotTransferableToAnotherCommand() throws Exception {
		final PlayerSession session = session("A", AuthState.AUTHENTICATED, NOW.plusSeconds(60));
		final Duration window = Duration.ofSeconds(30);

		session.confirm("premium", NOW, window);
		assertFalse(session.confirm("cracked", NOW.plusSeconds(5), window), "another command must not use it");
		assertFalse(session.confirm("cracked", NOW.plusSeconds(40), window), "and it expires");
		assertTrue(session.confirm("cracked", NOW.plusSeconds(41), window));
	}

	@Test
	void onlyOneCommandThatChangesTheAccountRunsAtATime() throws Exception {
		final PlayerSession session = session("A", AuthState.AUTHENTICATED, NOW.plusSeconds(60));

		assertTrue(session.tryBegin());
		assertFalse(session.tryBegin());

		session.end();

		assertTrue(session.tryBegin());
	}

	@Test
	void theManagerFindsPlayersByUuidAndByNameIgnoringCase() throws Exception {
		final SessionManager manager = new SessionManager();
		final PlayerSession steve = session("Steve", AuthState.PENDING_LOGIN, NOW.plusSeconds(60));

		manager.add(steve);

		assertSame(steve, manager.find(steve.uuid()).orElseThrow());
		assertSame(steve, manager.findByName("STEVE").orElseThrow());
		assertTrue(manager.isOnline("steve"));
		assertFalse(manager.isOnline("Alex"));
		assertEquals(1, manager.size());
	}

	@Test
	void removingAnOldSessionLeavesTheNewerOneOfTheSamePlayer() throws Exception {
		final SessionManager manager = new SessionManager();
		final UUID uuid = UUID.randomUUID();
		final PlayerSession old = new PlayerSession(uuid, "Steve", InetAddress.getByName("203.0.113.7"), NOW, NOW.plusSeconds(60), false, AuthState.PENDING_LOGIN, null, null);
		final PlayerSession fresh = new PlayerSession(uuid, "Steve", InetAddress.getByName("203.0.113.7"), NOW, NOW.plusSeconds(60), false, AuthState.PENDING_LOGIN, null, null);

		manager.add(old);
		manager.add(fresh); // the player reconnected before the old connection's disconnect was handled
		manager.remove(old);

		assertSame(fresh, manager.find(uuid).orElseThrow());
		assertTrue(manager.isOnline("Steve"));

		manager.remove(fresh);

		assertFalse(manager.isOnline("Steve"));
		assertEquals(0, manager.size());
	}

	@Test
	void theManagerListsOnlyWaitingPlayersWhoseTimeIsUp() throws Exception {
		final SessionManager manager = new SessionManager();
		final PlayerSession late = session("Late", AuthState.PENDING_LOGIN, NOW.plusSeconds(10));
		final PlayerSession early = session("Early", AuthState.PENDING_REGISTER, NOW.plusSeconds(100));
		final PlayerSession done = session("Done", AuthState.PENDING_LOGIN, NOW.plusSeconds(10));

		done.authenticate(AuthMethod.SESSION, null);
		manager.add(late);
		manager.add(early);
		manager.add(done);

		assertEquals(List.of(late), manager.expired(NOW.plusSeconds(11)));
		assertEquals(2, manager.expired(NOW.plusSeconds(101)).size());
		assertEquals(3, manager.all().size());
	}
}
