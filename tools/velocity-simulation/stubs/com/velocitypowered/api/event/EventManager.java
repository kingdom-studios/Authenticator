// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event;
public interface EventManager {
	void register(Object plugin, Object listener);
	void unregisterListeners(Object plugin);
	void unregisterListener(Object plugin, Object listener);
}
