package pl.kingdomcraft.authenticator.velocity.listener;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;

import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Decision;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Request;
import pl.kingdomcraft.authenticator.common.proxy.ProxyCore;
import pl.kingdomcraft.authenticator.common.shadow.ShadowComparator.Observed;

/**
 * Shadow mode: runs after every other plugin (last), looks at what they made of the connection, and writes down whether Authenticator would
 * have decided the same. It never calls a setter on an event, so it cannot change anything for a player.
 */
public final class ShadowListener {

	private final ProxyCore core;
	private final Logger logger;

	public ShadowListener(ProxyCore core, Logger logger) {
		this.core = core;
		this.logger = logger;
	}

	@Subscribe(order = PostOrder.LAST)
	public EventTask onPreLogin(PreLoginEvent event) {
		return EventTask.async(() -> {
			try {
				final Decision ours = this.core.decider().decide(new Request(event.getUsername(), event.getConnection().getRemoteAddress().getAddress(), event.getUniqueId()));

				this.core.shadow().compare(event.getUsername(), ours, observe(event.getResult()));

			} catch (final RuntimeException ex) {
				this.logger.log(Level.WARNING, "Shadow comparison failed for " + event.getUsername(), ex);
			}
		});
	}

	@Subscribe(order = PostOrder.LAST)
	public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
		return EventTask.async(() -> {
			try {
				final UUID ours = this.core.auth().profileUuid(event.getUsername(), event.isOnlineMode(), event.getOriginalProfile().getId());

				this.core.shadow().compareUuid(event.getUsername(), ours, event.getGameProfile().getId());

			} catch (final RuntimeException ex) {
				this.logger.log(Level.WARNING, "Shadow UUID comparison failed for " + event.getUsername(), ex);
			}
		});
	}

	/** What the connection will be, after everyone else has had their say. */
	static Observed observe(PreLoginComponentResult result) {
		if (!result.isAllowed())
			return Observed.DENIED;

		if (result.isForceOfflineMode())
			return Observed.OFFLINE;

		if (result.isOnlineModeAllowed())
			return Observed.ONLINE;

		return Observed.UNDECIDED;
	}
}
