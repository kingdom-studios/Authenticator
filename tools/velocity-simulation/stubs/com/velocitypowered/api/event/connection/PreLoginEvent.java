// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event.connection;
import java.util.UUID;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import net.kyori.adventure.text.Component;
public interface PreLoginEvent extends ResultedEvent<PreLoginEvent.PreLoginComponentResult> {
	InboundConnection getConnection();
	String getUsername();
	UUID getUniqueId();
	final class PreLoginComponentResult implements ResultedEvent.Result {
		private enum Kind { ALLOWED, DENIED, ONLINE, OFFLINE }
		private final Kind kind; private final Component reason;
		private PreLoginComponentResult(Kind kind, Component reason) { this.kind = kind; this.reason = reason; }
		public static PreLoginComponentResult allowed() { return new PreLoginComponentResult(Kind.ALLOWED, null); }
		public static PreLoginComponentResult denied(Component reason) { return new PreLoginComponentResult(Kind.DENIED, reason); }
		public static PreLoginComponentResult forceOnlineMode() { return new PreLoginComponentResult(Kind.ONLINE, null); }
		public static PreLoginComponentResult forceOfflineMode() { return new PreLoginComponentResult(Kind.OFFLINE, null); }
		@Override public boolean isAllowed() { return this.kind != Kind.DENIED; }
		public boolean isOnlineModeAllowed() { return this.kind == Kind.ONLINE; }
		public boolean isForceOfflineMode() { return this.kind == Kind.OFFLINE; }
		public Component reason() { return this.reason; }
		@Override public String toString() { return this.kind.name(); }
	}
}
