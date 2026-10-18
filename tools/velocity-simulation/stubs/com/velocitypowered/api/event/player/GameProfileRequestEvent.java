// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.event.player;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.util.GameProfile;
public interface GameProfileRequestEvent {
	InboundConnection getConnection();
	String getUsername();
	GameProfile getOriginalProfile();
	boolean isOnlineMode();
	GameProfile getGameProfile();
	void setGameProfile(GameProfile gameProfile);
}
