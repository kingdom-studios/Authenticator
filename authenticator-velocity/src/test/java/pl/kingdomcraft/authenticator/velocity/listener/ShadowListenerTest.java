package pl.kingdomcraft.authenticator.velocity.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;

import net.kyori.adventure.text.Component;
import pl.kingdomcraft.authenticator.common.shadow.ShadowComparator.Observed;

/** What another plugin made of a connection, read with the real Velocity result type. */
class ShadowListenerTest {

	@Test
	void everyKindOfResultIsRecognised() {
		assertEquals(Observed.ONLINE, ShadowListener.observe(PreLoginComponentResult.forceOnlineMode()));
		assertEquals(Observed.OFFLINE, ShadowListener.observe(PreLoginComponentResult.forceOfflineMode()));
		assertEquals(Observed.DENIED, ShadowListener.observe(PreLoginComponentResult.denied(Component.text("no"))));
		assertEquals(Observed.UNDECIDED, ShadowListener.observe(PreLoginComponentResult.allowed()), "nobody decided: Velocity's own setting applies, which cannot be compared");
	}
}
