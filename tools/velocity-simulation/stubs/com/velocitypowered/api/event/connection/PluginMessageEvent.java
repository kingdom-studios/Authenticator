// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event.connection;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
public interface PluginMessageEvent extends ResultedEvent<PluginMessageEvent.ForwardResult> {
	ChannelMessageSource getSource();
	ChannelMessageSink getTarget();
	ChannelIdentifier getIdentifier();
	byte[] getData();
	final class ForwardResult implements ResultedEvent.Result {
		private final boolean forward;
		private ForwardResult(boolean forward) { this.forward = forward; }
		public static ForwardResult forward() { return new ForwardResult(true); }
		public static ForwardResult handled() { return new ForwardResult(false); }
		@Override public boolean isAllowed() { return this.forward; }
	}
}
