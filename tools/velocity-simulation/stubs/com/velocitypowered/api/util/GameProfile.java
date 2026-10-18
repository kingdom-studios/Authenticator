// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.util;
import java.util.UUID;
public final class GameProfile {
	private final UUID id; private final String name;
	public GameProfile(UUID id, String name) { this.id = id; this.name = name; }
	public UUID getId() { return this.id; }
	public String getName() { return this.name; }
	public GameProfile withId(UUID id) { return new GameProfile(id, this.name); }
}
