// STUB: hand-written subset of the Velocity API, only to check that the adapter is internally consistent. Not the real API.
package com.velocitypowered.api.proxy.server;
import java.net.InetSocketAddress;
public final class ServerInfo {
	private final String name;
	public ServerInfo(String name, InetSocketAddress address) { this.name = name; }
	public String getName() { return this.name; }
}
