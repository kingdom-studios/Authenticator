// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event.player;
import java.util.Optional;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
public interface PlayerChooseInitialServerEvent {
	Player getPlayer();
	Optional<RegisteredServer> getInitialServer();
	void setInitialServer(RegisteredServer server);
}
