// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.command;
public interface CommandMeta {
	interface Builder { Builder aliases(String... aliases); Builder plugin(Object plugin); CommandMeta build(); }
}
