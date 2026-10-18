// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.proxy.server;
import java.util.Collection;
import com.velocitypowered.api.proxy.Player;
public interface RegisteredServer {
	ServerInfo getServerInfo();
	Collection<Player> getPlayersConnected();
}
