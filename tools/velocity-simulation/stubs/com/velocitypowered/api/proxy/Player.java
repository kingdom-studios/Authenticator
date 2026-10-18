// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.proxy;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
public interface Player extends CommandSource, InboundConnection, ChannelMessageSource, ChannelMessageSink {
	String getUsername();
	UUID getUniqueId();
	Optional<ServerConnection> getCurrentServer();
	@Override InetSocketAddress getRemoteAddress();
	boolean isOnlineMode();
	boolean isActive();
	void disconnect(Component reason);
	ConnectionRequestBuilder createConnectionRequest(RegisteredServer server);
}
