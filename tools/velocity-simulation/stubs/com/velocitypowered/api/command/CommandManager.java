// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.command;
public interface CommandManager {
	CommandMeta.Builder metaBuilder(String alias);
	void register(CommandMeta meta, Command command);
}
