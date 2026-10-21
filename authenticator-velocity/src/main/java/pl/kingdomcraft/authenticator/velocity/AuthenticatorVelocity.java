package pl.kingdomcraft.authenticator.velocity;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import pl.kingdomcraft.authenticator.common.config.ConfigException;

/**
 * Authenticator on the proxy: decides, for every connection, whether the player is premium (Mojang confirms it, no password) or must log in
 * with a password, and keeps everyone who has not logged in on a waiting server.
 *
 * <p>If the plugin cannot start (a bad config, no database) it says so loudly and does nothing: connections then fall back to Velocity's own
 * online mode, which lets in original accounts only. It never lets anyone in unchecked.
 */
@Plugin(id = "authenticator", name = "Authenticator", version = "1.0.0", description = "Premium detection and password login for the network", authors = { "cammaj" })
public final class AuthenticatorVelocity {

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;

	private volatile VelocityRuntime runtime;

	@Inject
	public AuthenticatorVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		try {
			this.runtime = VelocityRuntime.start(this, this.proxy, this.logger, this.dataDirectory);

		} catch (final ConfigException ex) {
			this.logger.error("Authenticator is NOT running: fix config.yml and restart the proxy. Only original accounts can join until then.\n{}", ex.getMessage());

		} catch (final Exception ex) {
			this.logger.error("Authenticator is NOT running because it failed to start. Only original accounts can join until then.", ex);
		}
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		final VelocityRuntime current = this.runtime;

		if (current != null)
			current.close();
	}
}
