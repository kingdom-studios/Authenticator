// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event.connection;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.proxy.Player;
public interface LoginEvent extends ResultedEvent<ResultedEvent.ComponentResult> { Player getPlayer(); }
