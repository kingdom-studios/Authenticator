package pl.kingdomcraft.authenticator.common.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.gate.Gate.Change;

class GateTest {

	private final Gate gate = new Gate();
	private final UUID player = UUID.randomUUID();

	@Test
	void anUnknownPlayerIsNotLoggedIn() {
		assertFalse(this.gate.authenticated(this.player));
		assertTrue(this.gate.get(this.player).isEmpty());
	}

	@Test
	void onlyAnAuthenticatedStateLetsAPlayerThrough() {
		assertEquals(Change.UPDATED, this.gate.apply(this.player, AuthState.PENDING_LOGIN, null, 1));
		assertFalse(this.gate.authenticated(this.player));

		assertEquals(Change.UPDATED, this.gate.apply(this.player, AuthState.PENDING_REGISTER, null, 2));
		assertFalse(this.gate.authenticated(this.player));

		assertEquals(Change.AUTHENTICATED, this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 3));
		assertTrue(this.gate.authenticated(this.player));
	}

	@Test
	void aNewPlayerWhoIsLoggedInAtFirstSightIsReportedAsAuthenticated() {
		assertEquals(Change.AUTHENTICATED, this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PREMIUM, 1));
	}

	@Test
	void takingTheLoginBackIsReported() {
		this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 1);

		assertEquals(Change.DEAUTHENTICATED, this.gate.apply(this.player, AuthState.PENDING_LOGIN, null, 2));
		assertFalse(this.gate.authenticated(this.player));
	}

	@Test
	void aLateMessageDoesNotUndoAnewerOne() {
		this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 5);

		assertEquals(Change.IGNORED, this.gate.apply(this.player, AuthState.PENDING_LOGIN, null, 4), "a logout that was sent before the login");
		assertTrue(this.gate.authenticated(this.player));

		this.gate.apply(this.player, AuthState.PENDING_LOGIN, null, 6);

		assertEquals(Change.IGNORED, this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 5), "and the other way round: an old login cannot log the player in again");
		assertFalse(this.gate.authenticated(this.player));
	}

	@Test
	void theSameReportTwiceChangesNothing() {
		this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 5);

		assertEquals(Change.UNCHANGED, this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 5));
	}

	@Test
	void anotherStateAtTheSameEpochIsTakenSoNoUpdateIsEverLost() {
		this.gate.apply(this.player, AuthState.PENDING_LOGIN, null, 5);

		// Two writers may use the same epoch only through a bug or a restored backup; the safe reading is to believe the newest report.
		assertEquals(Change.AUTHENTICATED, this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.FORCED, 5));
	}

	@Test
	void anAuthenticatedStateNeedsAMethodAndNothingElseMayHaveOne() {
		assertThrows(IllegalArgumentException.class, () -> this.gate.apply(this.player, AuthState.AUTHENTICATED, null, 1));
		assertThrows(IllegalArgumentException.class, () -> this.gate.apply(this.player, AuthState.PENDING_LOGIN, AuthMethod.PASSWORD, 1));
		assertFalse(this.gate.authenticated(this.player), "and the refused report left nothing behind");
	}

	@Test
	void aRemovedPlayerIsNotLoggedInAndTheirEpochIsForgotten() {
		this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 50);
		this.gate.remove(this.player);

		assertFalse(this.gate.authenticated(this.player));
		assertEquals(Change.UPDATED, this.gate.apply(this.player, AuthState.PENDING_LOGIN, null, 1), "a new connection starts counting again");
	}

	@Test
	void theCountsAndTheKnownSetFollowTheEntries() {
		final UUID other = UUID.randomUUID();

		this.gate.apply(this.player, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 1);
		this.gate.apply(other, AuthState.PENDING_LOGIN, null, 1);

		assertEquals(2, this.gate.size());
		assertEquals(1, this.gate.authenticatedCount());
		assertEquals(java.util.Set.of(this.player, other), this.gate.known());
	}
}
