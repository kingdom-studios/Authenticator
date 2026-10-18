package pl.kingdomcraft.authenticator.velocity.bridge;

import java.time.Clock;
import java.util.Optional;
import java.util.logging.Logger;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport;
import pl.kingdomcraft.authenticator.common.bridge.Recipient;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;

/**
 * Carries the bridge messages over the connection of the player they are about: the proxy sends to the backend server the player is on, and
 * hears from backend servers. Nothing here decides what is trusted; it reports where each message came from, and the common transport
 * drops everything a player's client sent and checks the signature of the rest.
 */
public final class VelocityPluginMessaging extends PluginMessagingTransport {

	public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier.create("authenticator", "bridge");

	private final ProxyServer proxy;
	private final Object plugin;

	public VelocityPluginMessaging(ProxyServer proxy, Object plugin, EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger) {
		super(verifier, nodeId, clock, logger);

		this.proxy = proxy;
		this.plugin = plugin;
	}

	/** Registers the channel: Velocity only reports messages (and only stops a client's messages) on channels a plugin has registered. */
	@Override
	public void start() {
		this.proxy.getChannelRegistrar().register(IDENTIFIER);
		this.proxy.getEventManager().register(this.plugin, this);
	}

	@Subscribe
	public void onPluginMessage(PluginMessageEvent event) {
		if (!IDENTIFIER.equals(event.getIdentifier()))
			return;

		// Never passed on, in either direction: a message from a server is for this plugin only, and one from a client must not reach a server.
		event.setResult(PluginMessageEvent.ForwardResult.handled());

		// A message arrives from a ServerConnection (a backend server) or from a Player (a client). Only the first is a peer.
		final Origin origin = event.getSource() instanceof ServerConnection ? Origin.TRUSTED_PEER : Origin.CLIENT;

		this.receive(origin, event.getData());
	}

	@Override
	protected boolean deliver(Recipient recipient, byte[] data) {
		if (recipient.kind() != Recipient.Kind.SERVER_OF)
			return false; // the proxy never sends to itself

		final Optional<Player> player = this.proxy.getPlayer(recipient.player());

		if (player.isEmpty())
			return false;

		final Optional<ServerConnection> connection = player.get().getCurrentServer();

		return connection.isPresent() && connection.get().sendPluginMessage(IDENTIFIER, data);
	}

	@Override
	public void close() {
		this.proxy.getEventManager().unregisterListener(this.plugin, this);
		this.proxy.getChannelRegistrar().unregister(IDENTIFIER);

		super.close();
	}
}
