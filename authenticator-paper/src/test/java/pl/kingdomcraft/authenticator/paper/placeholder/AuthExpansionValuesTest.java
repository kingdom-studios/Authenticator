package pl.kingdomcraft.authenticator.paper.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.gate.AccountSnapshots.Snapshot;

class AuthExpansionValuesTest {

	@Test
	void onlyTheThreePlaceholdersAreKnown() {
		assertTrue(AuthExpansion.Values.isKnown("state"));
		assertTrue(AuthExpansion.Values.isKnown("premium"));
		assertTrue(AuthExpansion.Values.isKnown("registered"));
		assertFalse(AuthExpansion.Values.isKnown("password"));
		assertFalse(AuthExpansion.Values.isKnown(""));
	}

	@Test
	void theStateIsWhatTheGateKnows() {
		assertEquals("authenticated", AuthExpansion.Values.resolve("state", AuthState.AUTHENTICATED, null));
		assertEquals("pending_login", AuthExpansion.Values.resolve("state", AuthState.PENDING_LOGIN, null));
		assertEquals("pending_register", AuthExpansion.Values.resolve("state", AuthState.PENDING_REGISTER, null));
		assertEquals("unknown", AuthExpansion.Values.resolve("state", null, null));
	}

	@Test
	void theAccountValuesComeFromTheSnapshotAndSayLoadingUntilThereIsOne() {
		assertEquals("loading", AuthExpansion.Values.resolve("premium", null, null));
		assertEquals("loading", AuthExpansion.Values.resolve("registered", null, null));
		assertEquals("true", AuthExpansion.Values.resolve("premium", null, new Snapshot(true, true)));
		assertEquals("false", AuthExpansion.Values.resolve("premium", null, new Snapshot(true, false)));
		assertEquals("true", AuthExpansion.Values.resolve("registered", null, new Snapshot(true, false)));
		assertEquals("false", AuthExpansion.Values.resolve("registered", null, new Snapshot(false, false)));
	}

	@Test
	void theStateDoesNotNeedASnapshot() {
		assertEquals("authenticated", AuthExpansion.Values.resolve("state", AuthState.AUTHENTICATED, null), "it never waits for the database");
	}
}
