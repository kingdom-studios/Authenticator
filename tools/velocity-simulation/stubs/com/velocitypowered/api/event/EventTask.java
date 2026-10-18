// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event;
public interface EventTask { static EventTask async(Runnable task) { task.run(); return new EventTask() { }; } }
