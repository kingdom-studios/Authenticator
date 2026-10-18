package pl.kingdomcraft.authenticator.common.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.routing.RoutingPolicy.Verdict;
import pl.kingdomcraft.authenticator.common.settings.RoutingSettings;

class RoutingPolicyTest {

	private final RoutingPolicy policy = new RoutingPolicy(new RoutingSettings(List.of("auth-1", "Auth-2"), List.of("lobby-1", "lobby-2")));

	/** A server that is not in the map does not exist. */
	private static ToIntFunction<String> load(Map<String, Integer> players) {
		return name -> players.getOrDefault(name, -1);
	}

	@Test
	void theLeastLoadedExistingServerIsChosen() {
		assertEquals(Optional.of("Auth-2"), this.policy.chooseAuth(load(Map.of("auth-1", 5, "Auth-2", 2))));
		assertEquals(Optional.of("lobby-1"), this.policy.chooseLobby(load(Map.of("lobby-1", 0, "lobby-2", 9))));
	}

	@Test
	void theFirstListedWinsATie() {
		assertEquals(Optional.of("lobby-1"), this.policy.chooseLobby(load(Map.of("lobby-1", 3, "lobby-2", 3))));
	}

	@Test
	void aServerThatDoesNotExistIsSkippedAndNoServerMeansNoChoice() {
		assertEquals(Optional.of("lobby-2"), this.policy.chooseLobby(load(Map.of("lobby-2", 40))));
		assertEquals(Optional.empty(), this.policy.chooseAuth(load(Map.of())));
	}

	@Test
	void anEmptyServerIsNotMistakenForAMissingOne() {
		assertEquals(Optional.of("auth-1"), this.policy.chooseAuth(load(Map.of("auth-1", 0))));
	}

	@Test
	void whereAPlayerStartsDependsOnWhetherTheyAreLoggedIn() {
		final ToIntFunction<String> everything = load(Map.of("auth-1", 1, "Auth-2", 2, "lobby-1", 3, "lobby-2", 4));

		assertEquals(Optional.of("auth-1"), this.policy.initial(false, everything));
		assertEquals(Optional.of("lobby-1"), this.policy.initial(true, everything));
	}

	@Test
	void serverNamesAreComparedIgnoringCase() {
		assertTrue(this.policy.isAuthServer("AUTH-2"));
		assertTrue(this.policy.isAuthServer("auth-2"));
		assertFalse(this.policy.isAuthServer("lobby-1"));
		assertFalse(this.policy.isAuthServer("auth"));
	}

	@Test
	void anUnauthenticatedPlayerMayOnlyReachAWaitingServer() {
		assertEquals(Verdict.ALLOW, this.policy.connect(false, true, "auth-1"));
		assertEquals(Verdict.ALLOW, this.policy.connect(false, false, "AUTH-2"));
		assertEquals(Verdict.DENY, this.policy.connect(false, true, "lobby-1"), "already somewhere: stay there");
		assertEquals(Verdict.REDIRECT_TO_AUTH, this.policy.connect(false, false, "lobby-1"), "not yet anywhere: start on a waiting server");
		assertEquals(Verdict.REDIRECT_TO_AUTH, this.policy.connect(false, false, "survival"));
	}

	@Test
	void anAuthenticatedPlayerMayGoAnywhere() {
		assertEquals(Verdict.ALLOW, this.policy.connect(true, true, "survival"));
		assertEquals(Verdict.ALLOW, this.policy.connect(true, false, "lobby-1"));
		assertEquals(Verdict.ALLOW, this.policy.connect(true, true, "auth-1"));
	}

	@Test
	void afterLoggingInAPlayerOnAWaitingServerMovesToALobby() {
		final ToIntFunction<String> everything = load(Map.of("auth-1", 1, "lobby-1", 5, "lobby-2", 1));

		assertEquals(Optional.of("lobby-2"), this.policy.moveAfterChange(true, "auth-1", everything));
		assertEquals(Optional.empty(), this.policy.moveAfterChange(true, "lobby-1", everything), "already in the game");
		assertEquals(Optional.empty(), this.policy.moveAfterChange(true, null, everything), "still connecting: the initial choice covers it");
	}

	@Test
	void afterBeingLoggedOutAPlayerInTheGameMovesToAWaitingServer() {
		final ToIntFunction<String> everything = load(Map.of("auth-1", 1, "Auth-2", 0, "lobby-1", 5));

		assertEquals(Optional.of("Auth-2"), this.policy.moveAfterChange(false, "lobby-1", everything));
		assertEquals(Optional.empty(), this.policy.moveAfterChange(false, "auth-1", everything));
		assertEquals(Optional.empty(), this.policy.moveAfterChange(false, null, everything));
	}

	@Test
	void noLobbyMeansThePlayerStaysWhereTheyAre() {
		assertEquals(Optional.empty(), this.policy.moveAfterChange(true, "auth-1", load(Map.of("auth-1", 1))));
	}

	@Test
	void settingsNeedAtLeastOneServerOfEachKind() {
		assertThrows(IllegalArgumentException.class, () -> new RoutingSettings(List.of(), List.of("lobby")));
		assertThrows(IllegalArgumentException.class, () -> new RoutingSettings(List.of("auth"), List.of()));
	}
}
