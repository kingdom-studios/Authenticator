package pl.kingdomcraft.authenticator.paper.gate;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import pl.kingdomcraft.authenticator.common.gate.BackendCore;
import pl.kingdomcraft.authenticator.common.gate.JoinDiagnostics;
import pl.kingdomcraft.authenticator.common.gate.JoinVerifier;
import pl.kingdomcraft.authenticator.paper.AuthenticatorPaper;
import pl.kingdomcraft.authenticator.paper.settings.Localization;

/**
 * The door. Before a player is let in, the proxy's record of them is read (off the main thread, in the asynchronous pre-login event):
 * <ul>
 * <li>no record: they did not come through the proxy, refused;</li>
 * <li>a {@code GAME} server and not logged in: refused;</li>
 * <li>the database cannot be read: refused, because nothing can be said about the player;</li>
 * <li>while the plugin is still starting: refused, because nothing is known yet.</li>
 * </ul>
 * When they join, a player who has not logged in is frozen at once, on the join event itself, with no delay and no repeated checks; the
 * verifier watches the database for them from then on and the bridge may tell this server sooner.
 */
@AutoRegister
public final class JoinListener implements Listener {

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPreLogin(AsyncPlayerPreLoginEvent event) {
		if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED)
			return; // another plugin has already refused this one

		final BackendCore core = AuthenticatorPaper.core();

		if (core == null) {
			event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, text(Localization.Gate.STARTING));

			return;
		}

		final JoinVerifier.LoginDecision decision = core.verifier().checkLogin(event.getUniqueId());

		if (decision.reason() == JoinVerifier.Reason.UNKNOWN_TO_PROXY)
			for (final String line : JoinDiagnostics.unknownToProxy(event.getName(), event.getUniqueId(),
					core.settings().database().database() + " on " + core.database().endpoint().address(), core.settings().nodeId()))
				Common.log("&c" + line);

		if (!decision.allowed())
			event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, text(switch (decision.reason()) {
				case NOT_AUTHENTICATED -> Localization.Gate.REFUSED_NOT_LOGGED_IN;
				case UNKNOWN_TO_PROXY -> Localization.Gate.REFUSED_UNKNOWN;
				default -> Localization.Gate.REFUSED_DATABASE;
			}));
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onJoin(PlayerJoinEvent event) {
		final BackendCore core = AuthenticatorPaper.core();
		final JoinVerifier.Role role = core != null ? core.verifier().role() : pl.kingdomcraft.authenticator.paper.settings.Settings.Server.ROLE;

		AuthenticatorPaper.freezer().joined(event.getPlayer(), role);

		if (core != null) {
			core.verifier().joined(event.getPlayer().getUniqueId());
			core.snapshots().refresh(event.getPlayer().getUniqueId());

		} else {
			Common.tell(event.getPlayer(), Localization.Gate.STARTING);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		final BackendCore core = AuthenticatorPaper.core();

		AuthenticatorPaper.freezer().left(event.getPlayer());
		AuthenticatorPaper.gate().remove(event.getPlayer().getUniqueId());
		AuthenticatorPaper.forget(event.getPlayer().getUniqueId());

		if (core != null) {
			core.verifier().left(event.getPlayer().getUniqueId());
			core.snapshots().forget(event.getPlayer().getUniqueId());
		}
	}

	private static Component text(String legacy) {
		return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
	}
}
