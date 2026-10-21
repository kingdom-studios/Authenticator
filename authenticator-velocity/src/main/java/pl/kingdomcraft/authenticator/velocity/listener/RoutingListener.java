package pl.kingdomcraft.authenticator.velocity.listener;

import java.util.Optional;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.routing.RoutingPolicy;
import pl.kingdomcraft.authenticator.velocity.Texts;
import pl.kingdomcraft.authenticator.velocity.VelocityRuntime;

/**
 * Keeps players who have not logged in on a waiting server. The rule is enforced where a connection to a server is made
 * ({@link ServerPreConnectEvent}), so no way of reaching a server (a command, another plugin, a forced host) goes around it; the initial
 * choice only makes the first connection go to the right place at once.
 *
 * <p>These handlers run last so that the final say is ours, and they only read memory, so they do not need to be asynchronous.
 */
public final class RoutingListener {

	private final VelocityRuntime runtime;
	private final RoutingPolicy routing;
	private final Texts texts;

	public RoutingListener(VelocityRuntime runtime, RoutingPolicy routing, Texts texts) {
		this.runtime = runtime;
		this.routing = routing;
		this.texts = texts;
	}

	@Subscribe(order = PostOrder.LAST)
	public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
		final Player player = event.getPlayer();
		final PlayerSession session = this.runtime.session(player);

		if (session == null) {
			player.disconnect(this.texts.render("open.unverified-profile")); // no session means the login machinery did not run: fail closed

			return;
		}

		final Optional<RegisteredServer> server = this.runtime.chooseInitial(session.authenticated());

		if (server.isPresent())
			event.setInitialServer(server.get());
		else
			player.disconnect(this.texts.render("kick.no-server"));
	}

	@Subscribe(order = PostOrder.LAST)
	public void onServerPreConnect(ServerPreConnectEvent event) {
		final Optional<RegisteredServer> target = event.getResult().getServer();

		if (target.isEmpty())
			return; // already refused

		final Player player = event.getPlayer();
		final PlayerSession session = this.runtime.session(player);

		if (session == null) {
			event.setResult(ServerPreConnectEvent.ServerResult.denied());
			player.disconnect(this.texts.render("open.unverified-profile"));

			return;
		}

		switch (this.routing.connect(session.authenticated(), player.getCurrentServer().isPresent(), target.get().getServerInfo().getName())) {
			case ALLOW -> {
			}

			case DENY -> {
				event.setResult(ServerPreConnectEvent.ServerResult.denied());
				player.sendMessage(this.texts.render("outcome.not-logged-in"));
			}

			case REDIRECT_TO_AUTH -> {
				final Optional<RegisteredServer> auth = this.runtime.chooseAuth();

				if (auth.isPresent()) {
					event.setResult(ServerPreConnectEvent.ServerResult.allowed(auth.get()));

				} else {
					event.setResult(ServerPreConnectEvent.ServerResult.denied());
					player.disconnect(this.texts.render("kick.no-server"));
				}
			}
		}
	}

	@Subscribe
	public void onServerPostConnect(ServerPostConnectEvent event) {
		this.runtime.arrived(event.getPlayer(), event.getPreviousServer() == null);
	}
}
