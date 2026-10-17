package pl.kingdomcraft.authenticator.paper;

import org.mineacademy.fo.plugin.SimplePlugin;

/**
 * Authenticator on a Paper server: freezes players until the proxy has authenticated them. Built on Foundation.
 *
 * <p>Stage 4 adds the login gate, the bridge to the proxy and the {@code /authadmin} commands; this class only exists so that the
 * module builds and loads.
 */
public final class AuthenticatorPaper extends SimplePlugin {

	@Override
	protected void onPluginStart() {
	}

	public static AuthenticatorPaper getInstance() {
		return (AuthenticatorPaper) SimplePlugin.getInstance();
	}
}
