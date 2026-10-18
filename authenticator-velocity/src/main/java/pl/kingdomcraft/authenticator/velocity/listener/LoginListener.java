package pl.kingdomcraft.authenticator.velocity.listener;

import java.net.InetAddress;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;

import pl.kingdomcraft.authenticator.common.auth.AuthService.Opened;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Decision;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Request;
import pl.kingdomcraft.authenticator.common.message.Messages;
import pl.kingdomcraft.authenticator.common.proxy.ProxyCore;
import pl.kingdomcraft.authenticator.velocity.Connections;
import pl.kingdomcraft.authenticator.velocity.Texts;

/**
 * The four moments a connection is decided and a session is opened:
 * <ol>
 * <li>{@link PreLoginEvent}: online (Mojang confirms the name), offline (password) or refused;</li>
 * <li>{@link GameProfileRequestEvent}: the UUID the player has on every server;</li>
 * <li>{@link LoginEvent}: the session is opened, or the login refused;</li>
 * <li>{@link DisconnectEvent}: the session is closed.</li>
 * </ol>
 * Everything that reads the database runs as an asynchronous event task. Whatever goes wrong, a connection is refused rather than let in
 * unchecked.
 */
public final class LoginListener {

	private final ProxyCore core;
	private final Connections connections;
	private final Texts texts;
	private final Logger logger;
	private final Consumer<Player> release;

	/** @param release closes the session of a connection; see {@code VelocityRuntime#release} */
	public LoginListener(ProxyCore core, Connections connections, Texts texts, Logger logger, Consumer<Player> release) {
		this.core = core;
		this.connections = connections;
		this.texts = texts;
		this.logger = logger;
		this.release = release;
	}

	/** Last, so that what this plugin decides is what stands. A login another plugin has already refused is left alone. */
	@Subscribe(order = PostOrder.LAST)
	public EventTask onPreLogin(PreLoginEvent event) {
		return EventTask.async(() -> {
			if (!event.getResult().isAllowed())
				return;

			try {
				final InetAddress address = event.getConnection().getRemoteAddress().getAddress();
				final Decision decision = this.core.decider().decide(new Request(event.getUsername(), address, event.getUniqueId()));

				switch (decision.mode()) {
					case ONLINE -> event.setResult(PreLoginComponentResult.forceOnlineMode());
					case OFFLINE -> event.setResult(PreLoginComponentResult.forceOfflineMode());
					case DENY -> event.setResult(PreLoginComponentResult.denied(this.texts.render(Messages.key(decision.reason()), Messages.denyValues(decision.reason(), decision.detail()))));
				}

				this.logger.fine(event.getUsername() + " (" + address.getHostAddress() + "): " + decision.mode() + " because " + decision.reason() + (decision.source().isEmpty() ? "" : " (" + decision.source() + ")"));

			} catch (final RuntimeException ex) {
				this.logger.log(Level.SEVERE, "Could not decide on the connection of " + event.getUsername() + "; it is refused.", ex);
				event.setResult(PreLoginComponentResult.denied(this.texts.render("deny.database-error")));
			}
		});
	}

	/** Last, so that a skin another plugin set is kept: only the UUID is replaced. */
	@Subscribe(order = PostOrder.LAST)
	public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
		return EventTask.async(() -> {
			final String name = event.getUsername();
			final GameProfile profile = event.getGameProfile();
			final UUID uuid = this.core.auth().profileUuid(name, event.isOnlineMode(), event.getOriginalProfile().getId());

			if (event.isOnlineMode())
				this.core.decider().claimSucceeded(name, event.getConnection().getRemoteAddress().getAddress());

			if (!uuid.equals(profile.getId()))
				event.setGameProfile(profile.withId(uuid));
		});
	}

	/** Late, so that a login another plugin (a ban, for instance) refused does not get a session. */
	@Subscribe(order = PostOrder.LATE)
	public EventTask onLogin(LoginEvent event) {
		return EventTask.async(() -> {
			if (!event.getResult().isAllowed())
				return;

			final Player player = event.getPlayer();

			try {
				final Opened opened = this.core.auth().open(player.getUsername(), player.getUniqueId(), player.getRemoteAddress().getAddress(), player.isOnlineMode());

				if (opened.session() == null) {
					event.setResult(ResultedEvent.ComponentResult.denied(this.texts.render(Messages.key(opened.failure()))));
					this.logger.info(player.getUsername() + " was refused: " + opened.failure());

					return;
				}

				this.connections.bind(player, opened.session());

			} catch (final RuntimeException ex) {
				this.logger.log(Level.SEVERE, "Could not open the session of " + player.getUsername() + "; the login is refused.", ex);
				event.setResult(ResultedEvent.ComponentResult.denied(this.texts.render("open.database-error")));
			}
		});
	}

	@Subscribe
	public EventTask onDisconnect(DisconnectEvent event) {
		return EventTask.async(() -> this.release.accept(event.getPlayer()));
	}
}
