package pl.kingdomcraft.authenticator.paper.gate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.mineacademy.fo.Common;

import pl.kingdomcraft.authenticator.common.gate.Gate;
import pl.kingdomcraft.authenticator.common.gate.JoinVerifier;
import pl.kingdomcraft.authenticator.paper.settings.Settings;

/**
 * What happens to a player the moment they are frozen or released: the inventory is closed, they are put at the login spawn on a waiting
 * server and hidden from the others (if the settings say so), and the reverse when the proxy has logged them in. The blocking itself is the
 * {@link Gatekeeper}'s job and does not depend on this; this only makes a frozen player look and stand right.
 *
 * <p>Everything here runs on the main thread. The {@link JoinVerifier.Listener} methods are called on another thread and hop over.
 */
public final class Freezer implements JoinVerifier.Listener {

	private final Plugin plugin;
	private final Gate gate;
	private final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
	private final Set<UUID> ownTeleports = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean warnedAboutSpawn = new AtomicBoolean();

	public Freezer(Plugin plugin, Gate gate) {
		this.plugin = plugin;
		this.gate = gate;
	}

	/** Whether a teleport of this player is one this plugin is making. */
	public boolean teleporting(Player player) {
		return this.ownTeleports.contains(player.getUniqueId());
	}

	@Override
	public void authenticated(UUID player) {
		Common.runLater(() -> {
			final Player online = Bukkit.getPlayer(player);

			if (online != null)
				this.release(online);
		});
	}

	@Override
	public void deauthenticated(UUID player) {
		Common.runLater(() -> {
			final Player online = Bukkit.getPlayer(player);

			if (online != null)
				this.freeze(online);
		});
	}

	/** A player has joined: freeze them if they have not logged in, and hide those who have not from them. Main thread. */
	public void joined(Player player, JoinVerifier.Role role) {
		if (!this.gate.authenticated(player.getUniqueId()))
			this.freeze(player, role == JoinVerifier.Role.AUTH);

		for (final UUID hiddenPlayer : this.hidden) {
			final Player target = Bukkit.getPlayer(hiddenPlayer);

			if (target != null && !target.equals(player))
				player.hidePlayer(this.plugin, target);
		}
	}

	/** A player has left. Main thread. */
	public void left(Player player) {
		this.hidden.remove(player.getUniqueId());
		this.ownTeleports.remove(player.getUniqueId());
	}

	/** Takes the player back to the waiting state. Main thread. */
	public void freeze(Player player) {
		this.freeze(player, Settings.Server.ROLE == JoinVerifier.Role.AUTH);
	}

	private void freeze(Player player, boolean waitingServer) {
		player.closeInventory();

		if (Settings.Gate.HIDE_UNAUTHENTICATED && this.hidden.add(player.getUniqueId()))
			for (final Player other : Bukkit.getOnlinePlayers())
				if (!other.equals(player))
					other.hidePlayer(this.plugin, player);

		if (waitingServer)
			this.toLoginSpawn(player);
	}

	/** The proxy has logged the player in. Main thread. */
	public void release(Player player) {
		if (this.hidden.remove(player.getUniqueId()))
			for (final Player other : Bukkit.getOnlinePlayers())
				if (!other.equals(player))
					other.showPlayer(this.plugin, player);
	}

	private void toLoginSpawn(Player player) {
		if (!Settings.LoginSpawn.configured())
			return;

		final World world = Bukkit.getWorld(Settings.LoginSpawn.WORLD);

		if (world == null) {
			if (this.warnedAboutSpawn.compareAndSet(false, true))
				Common.log("&cLogin_Spawn.World '" + Settings.LoginSpawn.WORLD + "' in settings.yml is not a loaded world, so players are not moved to the login spawn.");

			return;
		}

		final UUID uuid = player.getUniqueId();

		this.ownTeleports.add(uuid);
		player.teleportAsync(new Location(world, Settings.LoginSpawn.X, Settings.LoginSpawn.Y, Settings.LoginSpawn.Z, Settings.LoginSpawn.YAW.floatValue(), Settings.LoginSpawn.PITCH.floatValue()))
				.whenComplete((result, error) -> Common.runLater(() -> this.ownTeleports.remove(uuid)));
	}
}
