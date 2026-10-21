package pl.kingdomcraft.authenticator.common.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JoinDiagnosticsTest {

	@Test
	void theOfflineUuidIsTheOneVanillaServersGive() {
		// well known: the offline UUID of "Notch" is b50ad385-829d-3141-a216-7e7d7539ba7f
		assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), JoinDiagnostics.offlineUuid("Notch"));
	}

	@Test
	void anOfflineUuidPointsAtVelocityForwarding() {
		final List<String> lines = JoinDiagnostics.unknownToProxy("Notch", JoinDiagnostics.offlineUuid("Notch"), "authenticator on 10.0.0.5:3306", "paper-auth");

		assertTrue(lines.get(0).contains("Notch") && lines.get(0).contains("auth_presence") && lines.get(0).contains("authenticator on 10.0.0.5:3306") && lines.get(0).contains("paper-auth"), lines.get(0));
		assertTrue(lines.get(1).contains("offline-mode UUID") && lines.get(1).contains("player-info-forwarding-mode") && lines.get(1).contains("paper-global.yml"), lines.get(1));
	}

	@Test
	void anotherUuidPointsAtTheDatabase() {
		final List<String> lines = JoinDiagnostics.unknownToProxy("Notch", UUID.randomUUID(), "authenticator on 10.0.0.5:3306", "paper-auth");

		assertEquals(2, lines.size());
		assertFalse(lines.get(1).contains("offline-mode"), lines.get(1));
		assertTrue(lines.get(1).contains("same database") && lines.get(1).contains("Could not write the login state"), lines.get(1));
	}
}
