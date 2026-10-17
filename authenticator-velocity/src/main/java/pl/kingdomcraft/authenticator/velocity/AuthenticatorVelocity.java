package pl.kingdomcraft.authenticator.velocity;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * Authenticator on the proxy: decides, for every connection, whether the player is premium or must log in with a password.
 *
 * <p>Stage 3 adds that logic; this class only exists so that the module builds and loads.
 */
@Plugin(id = "authenticator", name = "Authenticator", version = "1.0.0", description = "Premium detection and password login for the network", authors = { "cammaj" })
public final class AuthenticatorVelocity {

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;

	@Inject
	public AuthenticatorVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		this.logger.info("Authenticator is loaded but not active yet: the login logic is not implemented in this build.");
	}
}
