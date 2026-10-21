package pl.kingdomcraft.authenticator.paper.bridge;

import java.time.Clock;
import java.util.Collection;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.mineacademy.fo.Common;

import pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport;
import pl.kingdomcraft.authenticator.common.bridge.Recipient;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;

/**
 * Carries the bridge messages over a player's connection: this server hears the proxy, and tells it about changes made here.
 *
 * <p>On a backend server every plugin message arrives "from a player": it is the player's connection that carries what the proxy sent. So
 * the source says nothing about who sent it, and everything is {@link Origin#TRUSTED_PEER} here. What keeps a client from forging one is
 * the signature on every message, the proxy dropping every message a client sends on this channel, and the backend ports being closed to
 * everyone but the proxy.
 */
public final class PaperPluginMessaging extends PluginMessagingTransport implements PluginMessageListener {

	private final Plugin plugin;

	public PaperPluginMessaging(Plugin plugin, EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger) {
		super(verifier, nodeId, clock, logger);

		this.plugin = plugin;
	}

	@Override
	public void start() {
		final Messenger messenger = Bukkit.getMessenger();

		messenger.registerIncomingPluginChannel(this.plugin, CHANNEL, this);
		messenger.registerOutgoingPluginChannel(this.plugin, CHANNEL);
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		if (CHANNEL.equals(channel))
			this.receive(Origin.TRUSTED_PEER, message);
	}

	/**
	 * The player the message is about carries it if they are here; otherwise any player will do, because it is the proxy that reads it, not
	 * the player.
	 */
	@Override
	protected boolean deliver(Recipient recipient, byte[] data) {
		if (recipient.kind() != Recipient.Kind.PROXY_VIA)
			return false; // a backend server never sends to itself or to another backend server

		Player carrier = Bukkit.getPlayer(recipient.player());

		if (carrier == null) {
			final Collection<? extends Player> online = Bukkit.getOnlinePlayers();

			if (online.isEmpty())
				return false; // nobody to carry it: the proxy finds out from the database

			carrier = online.iterator().next();
		}

		final Player chosen = carrier;

		Common.runLater(() -> chosen.sendPluginMessage(this.plugin, CHANNEL, data));

		return true;
	}

	@Override
	public void close() {
		final Messenger messenger = Bukkit.getMessenger();

		messenger.unregisterIncomingPluginChannel(this.plugin, CHANNEL, this);
		messenger.unregisterOutgoingPluginChannel(this.plugin, CHANNEL);

		super.close();
	}
}
