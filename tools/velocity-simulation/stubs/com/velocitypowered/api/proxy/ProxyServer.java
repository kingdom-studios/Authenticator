// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.proxy;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.Scheduler;
public interface ProxyServer {
	Optional<Player> getPlayer(UUID uuid);
	Optional<Player> getPlayer(String username);
	Collection<Player> getAllPlayers();
	Optional<RegisteredServer> getServer(String name);
	Collection<RegisteredServer> getAllServers();
	EventManager getEventManager();
	CommandManager getCommandManager();
	Scheduler getScheduler();
	ChannelRegistrar getChannelRegistrar();
	ProxyConfig getConfiguration();
}
