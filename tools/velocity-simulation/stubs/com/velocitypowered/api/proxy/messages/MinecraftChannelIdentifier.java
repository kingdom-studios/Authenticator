// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.proxy.messages;
public final class MinecraftChannelIdentifier implements ChannelIdentifier {
	private final String id;
	private MinecraftChannelIdentifier(String id) { this.id = id; }
	public static MinecraftChannelIdentifier create(String namespace, String name) { return new MinecraftChannelIdentifier(namespace + ":" + name); }
	@Override public String getId() { return this.id; }
	@Override public boolean equals(Object o) { return o instanceof MinecraftChannelIdentifier m && m.id.equals(this.id); }
	@Override public int hashCode() { return this.id.hashCode(); }
}
