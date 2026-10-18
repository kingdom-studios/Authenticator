// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event;
import java.util.Optional;
import net.kyori.adventure.text.Component;
public interface ResultedEvent<R extends ResultedEvent.Result> {
	R getResult();
	void setResult(R result);
	interface Result { boolean isAllowed(); }
	final class ComponentResult implements Result {
		private final Component reason;
		private ComponentResult(Component reason) { this.reason = reason; }
		public static ComponentResult allowed() { return new ComponentResult(null); }
		public static ComponentResult denied(Component reason) { return new ComponentResult(reason); }
		@Override public boolean isAllowed() { return this.reason == null; }
		public Optional<Component> getReasonComponent() { return Optional.ofNullable(this.reason); }
	}
}
