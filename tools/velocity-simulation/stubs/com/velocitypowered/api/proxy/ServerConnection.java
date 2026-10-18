// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.proxy;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
public interface ServerConnection extends ChannelMessageSource, ChannelMessageSink {
	RegisteredServer getServer();
	ServerInfo getServerInfo();
	Player getPlayer();
	boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data);
}
