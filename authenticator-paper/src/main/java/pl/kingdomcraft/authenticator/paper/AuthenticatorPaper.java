package pl.kingdomcraft.authenticator.paper;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.authenticator.common.gate.BackendCore;
import pl.kingdomcraft.authenticator.common.gate.Gate;
import pl.kingdomcraft.authenticator.common.settings.BackendSettings;
import pl.kingdomcraft.authenticator.paper.bridge.PaperPluginMessaging;
import pl.kingdomcraft.authenticator.paper.gate.Freezer;
import pl.kingdomcraft.authenticator.paper.gate.Gatekeeper;
import pl.kingdomcraft.authenticator.paper.settings.Localization;
import pl.kingdomcraft.authenticator.paper.settings.SettingsFile;
import pl.kingdomcraft.authenticator.paper.settings.Settings;

/**
 * Authenticator on a Paper server: nobody plays until the proxy has logged them in. Built on Foundation: it loads the settings and the
 * localization, registers the {@code @AutoRegister} classes (the join listener, the commands, the placeholders) and calls this class.
 *
 * <p>The {@link Gate} and the {@link Gatekeeper} exist from the first moment the plugin is enabled, before the database is touched: until the
 * proxy's word about a player has been read nobody passes. {@link BackendCore} (database, bridge) is built off the main thread and replaced
 * as a whole on reload; commands and listeners ask for {@link #core()} and treat null as "still starting".
 */
public final class AuthenticatorPaper extends SimplePlugin {

	/** Serializes starting and stopping, so a reload or shutdown during a slow start cannot leak connections. */
	private static final Object LIFECYCLE = new Object();

	private static final Gate GATE = new Gate();

	private static volatile BackendCore core;
	private static volatile Freezer freezer;
	private static volatile Gatekeeper gatekeeper;

	/** When a frozen player was last reminded, to keep the reminders from becoming spam. */
	private static final Map<UUID, Long> REMINDED = new ConcurrentHashMap<>();

	@Override
	protected void onPluginStart() {
		freezer = new Freezer(this, GATE);
		gatekeeper = new Gatekeeper(GATE, AuthenticatorPaper::remind, player -> freezer.teleporting(player));
		gatekeeper.register(this);

		this.startCoreAsync();
	}

	/** Settings have just been re-read when this runs, so the new core picks them up. */
	@Override
	protected void onPluginReload() {
		this.startCoreAsync();
	}

	@Override
	protected void onPluginPreReload() {
		stopCore();
	}

	@Override
	protected void onPluginStop() {
		stopCore();
	}

	/**
	 * Connecting to MariaDB can take seconds, so it never happens on the main thread. Until it finishes {@link #core()} is null: the door
	 * refuses everybody, commands say to wait, and players who are already here stay frozen.
	 */
	private void startCoreAsync() {
		Common.runAsync(() -> {
			synchronized (LIFECYCLE) {
				if (!this.isEnabled() || core != null)
					return;

				try {
					final BackendSettings settings = SettingsFile.load(this.getDataFolder().toPath().resolve("settings.yml"));
					final BackendCore started = BackendCore.start(settings, Settings.Server.ROLE, Settings.Gate.poll(), GATE, freezer, Clock.systemUTC(), this.getLogger(),
							(verifier, nodeId, clock, logger) -> new PaperPluginMessaging(this, verifier, nodeId, clock, logger), null);

					started.activate();
					core = started;

					Common.log("Ready: node '" + settings.nodeId() + "', role " + Settings.Server.ROLE + ", bridge " + settings.bridge().mode().key() + ".");

					// Players who were here before the core was ready (a reload, a slow start): their state is read now.
					Common.runLater(() -> {
						for (final Player player : Bukkit.getOnlinePlayers()) {
							started.verifier().joined(player.getUniqueId());
							started.snapshots().refresh(player.getUniqueId());
						}
					});

				} catch (final pl.kingdomcraft.authenticator.common.config.ConfigException | IllegalStateException ex) {
					Common.log("&cAuthenticator is NOT running: " + ex.getMessage() + " Until it is fixed nobody who has not been logged in can play on this server.");

				} catch (final Throwable t) {
					Common.error(t, "Authenticator could not start. Until it is fixed nobody who has not been logged in can play on this server.");
				}
			}
		});
	}

	private static void stopCore() {
		synchronized (LIFECYCLE) {
			final BackendCore current = core;
			core = null;

			if (current != null)
				current.close();
		}
	}

	private static void remind(Player player) {
		final int cooldown = Settings.Gate.MESSAGE_COOLDOWN_SECONDS;

		if (cooldown == 0)
			return;

		final long now = System.currentTimeMillis();
		final Long last = REMINDED.get(player.getUniqueId());

		if (last != null && now - last < cooldown * 1000L)
			return;

		REMINDED.put(player.getUniqueId(), now);
		Common.runLater(() -> Common.tell(player, Localization.Gate.LOGIN_FIRST));
	}

	/** Forgets a player's reminder time when they leave. */
	public static void forget(UUID player) {
		REMINDED.remove(player);
	}

	/** The running core, or null while starting, after a failed start, or while reloading. */
	public static BackendCore core() {
		return core;
	}

	public static Gate gate() {
		return GATE;
	}

	public static Freezer freezer() {
		return freezer;
	}

	public static Gatekeeper gatekeeper() {
		return gatekeeper;
	}

	public static AuthenticatorPaper getInstance() {
		return (AuthenticatorPaper) SimplePlugin.getInstance();
	}
}
