// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event.player;
import java.util.Optional;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
public interface ServerPreConnectEvent extends ResultedEvent<ServerPreConnectEvent.ServerResult> {
	Player getPlayer();
	RegisteredServer getOriginalServer();
	final class ServerResult implements ResultedEvent.Result {
		private final RegisteredServer server;
		private ServerResult(RegisteredServer server) { this.server = server; }
		public static ServerResult denied() { return new ServerResult(null); }
		public static ServerResult allowed(RegisteredServer server) { return new ServerResult(server); }
		@Override public boolean isAllowed() { return this.server != null; }
		public Optional<RegisteredServer> getServer() { return Optional.ofNullable(this.server); }
	}
}
