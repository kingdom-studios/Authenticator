// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event.player;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
public interface ServerPostConnectEvent {
	Player getPlayer();
	RegisteredServer getPreviousServer();
}
