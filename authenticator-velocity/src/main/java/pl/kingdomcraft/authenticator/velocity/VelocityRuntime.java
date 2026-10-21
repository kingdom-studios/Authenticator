package pl.kingdomcraft.authenticator.velocity;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.auth.StateWatcher;
import pl.kingdomcraft.authenticator.common.message.AuthCommands.Command;
import pl.kingdomcraft.authenticator.common.message.Messages;
import pl.kingdomcraft.authenticator.common.proxy.ProxyCore;
import pl.kingdomcraft.authenticator.common.routing.RoutingPolicy;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;
import pl.kingdomcraft.authenticator.velocity.bridge.VelocityPluginMessaging;
import pl.kingdomcraft.authenticator.velocity.command.AdminCommand;
import pl.kingdomcraft.authenticator.velocity.command.PlayerCommand;
import pl.kingdomcraft.authenticator.velocity.config.ConfigLoader;
import pl.kingdomcraft.authenticator.velocity.listener.LoginListener;
import pl.kingdomcraft.authenticator.velocity.listener.RoutingListener;
import pl.kingdomcraft.authenticator.velocity.listener.ShadowListener;
import pl.kingdomcraft.authenticator.velocity.util.Slf4jHandler;

/**
 * Wires the plugin together and owns what must be closed on shutdown. Startup order matters: configuration, database and schema, and only
 * then the listeners and commands, so no player is ever judged before the accounts can be read.
 *
 * <p>In shadow mode ({@code dry-run: true}) only the shadow listener and {@code /authadmin} are registered: no player command, no routing,
 * no bridge, nothing that could change a connection.
 */
public final class VelocityRuntime implements AutoCloseable {

	private static final long MAINTENANCE_MINUTES = 10;

	private final Object plugin;
	private final ProxyServer proxy;
	private final Logger logger;
	private final ProxyCore core;
	private final Texts texts;
	private final RoutingPolicy routing;
	private final Connections connections = new Connections();

	private volatile boolean closing;
	private final java.util.List<ScheduledTask> tasks = new java.util.concurrent.CopyOnWriteArrayList<>();
	private int ticks;

	private VelocityRuntime(Object plugin, ProxyServer proxy, Logger logger, ProxyCore core) {
		this.plugin = plugin;
		this.proxy = proxy;
		this.logger = logger;
		this.core = core;
		this.texts = new Texts(core.messages());
		this.routing = new RoutingPolicy(core.settings().routing());
	}

	/**
	 * @throws pl.kingdomcraft.authenticator.common.config.ConfigException if config.yml is invalid (nothing is started)
	 * @throws Exception                                                  if the database cannot be reached or migrated
	 */
	public static VelocityRuntime start(Object plugin, ProxyServer proxy, org.slf4j.Logger slf4j, Path dataDirectory) throws Exception {
		final Logger logger = Slf4jHandler.bridge("Authenticator", slf4j);

		ConfigLoader.ensureDefault(dataDirectory, "config.yml");
		ConfigLoader.update(dataDirectory, "config.yml", logger::info);

		final ProxySettings settings = ConfigLoader.load(dataDirectory.resolve("config.yml"));

		final ProxyCore core = ProxyCore.start(settings, Clock.systemUTC(), logger, (verifier, nodeId, clock, log) -> new VelocityPluginMessaging(proxy, plugin, verifier, nodeId, clock, log), null);

		try {
			final VelocityRuntime runtime = new VelocityRuntime(plugin, proxy, logger, core);

			runtime.checkProxySetup();
			runtime.checkServers();
			runtime.register();

			logger.info("Authenticator started as node '" + settings.nodeId() + "' in " + (settings.dryRun() ? "SHADOW mode (comparing only, changing nothing)" : "active mode")
					+ ", bridge: " + settings.bridge().mode().key() + ".");

			return runtime;

		} catch (final Exception | Error ex) {
			core.close();

			throw ex;
		}
	}

	private void register() {
		final ProxySettings settings = this.core.settings();

		this.proxy.getCommandManager().register(this.proxy.getCommandManager().metaBuilder("authadmin").plugin(this.plugin).build(),
				new AdminCommand(this.core, task -> this.proxy.getScheduler().buildTask(this.plugin, task).schedule()));

		if (settings.dryRun()) {
			this.core.activate(new StateWatcher.Handler() {
				@Override
				public void accountRemoved(PlayerSession session) {
				}

				@Override
				public void typeChanged(PlayerSession session) {
				}

				@Override
				public void stateForced(PlayerSession session) {
				}
			}, session -> {
			});

			this.proxy.getEventManager().register(this.plugin, new ShadowListener(this.core, this.logger));

			return;
		}

		this.core.activate(new StateWatcher.Handler() {
			@Override
			public void accountRemoved(PlayerSession session) {
				VelocityRuntime.this.playerOf(session).ifPresent(player -> player.sendMessage(VelocityRuntime.this.texts.render("prompt.register")));
			}

			@Override
			public void typeChanged(PlayerSession session) {
				VelocityRuntime.this.playerOf(session).ifPresent(player -> player.disconnect(VelocityRuntime.this.texts.render("kick.type-changed")));
			}

			@Override
			public void stateForced(PlayerSession session) {
				VelocityRuntime.this.playerOf(session).ifPresent(player -> player.sendMessage(VelocityRuntime.this.texts.render(session.authenticated() ? "forced.login" : "forced.logout")));
			}
		}, this::stateChanged);

		this.proxy.getEventManager().register(this.plugin, new LoginListener(this.core, this.connections, this.texts, this.logger, this::release));
		this.proxy.getEventManager().register(this.plugin, new RoutingListener(this, this.routing, this.texts));

		for (final Command command : Command.values())
			this.proxy.getCommandManager().register(this.proxy.getCommandManager().metaBuilder(command.commandName()).plugin(this.plugin).build(), new PlayerCommand(command, this.core, this.connections, this.texts));

		this.schedule(1, TimeUnit.SECONDS, this::tick);
		this.schedule(MAINTENANCE_MINUTES, TimeUnit.MINUTES, this.core::maintain);

		if (settings.bridge().databasePollSeconds() > 0)
			this.schedule(settings.bridge().databasePollSeconds(), TimeUnit.SECONDS, this.core::poll);
	}

	private void schedule(long every, TimeUnit unit, Runnable task) {
		this.tasks.add(this.proxy.getScheduler().buildTask(this.plugin, () -> {
			try {
				task.run();

			} catch (final RuntimeException ex) {
				this.logger.log(Level.WARNING, "A scheduled task failed", ex);
			}
		}).delay(every, unit).repeat(every, unit).schedule());
	}

	/** Warns about a setting of the proxy that makes the whole scheme unsafe. It cannot fix it, and it must not stop the plugin for it. */
	private void checkProxySetup() {
		if (this.core.settings().dryRun())
			return;

		if (!this.proxy.getConfiguration().isOnlineMode())
			this.logger.warning("velocity.toml has online-mode = false. Authenticator still makes every connection online or offline by itself, but a bug or an unloaded plugin would then let anyone in under any name. Set online-mode = true.");
	}

	private void checkServers() {
		final ProxySettings settings = this.core.settings();

		for (final String name : settings.routing().authServers())
			if (this.proxy.getServer(name).isEmpty())
				this.logger.warning("routing.auth-servers lists '" + name + "', but velocity.toml has no such server.");

		for (final String name : settings.routing().lobbyServers())
			if (this.proxy.getServer(name).isEmpty())
				this.logger.warning("routing.lobby-servers lists '" + name + "', but velocity.toml has no such server.");
	}

	// ---- what the listeners and commands ask for ----

	/** How many players a server has, or -1 if there is no such server. */
	public int load(String serverName) {
		return this.proxy.getServer(serverName).map(server -> server.getPlayersConnected().size()).orElse(-1);
	}

	public ProxyServer proxy() {
		return this.proxy;
	}

	public PlayerSession session(Player player) {
		return this.connections.session(player);
	}

	public Optional<RegisteredServer> chooseInitial(boolean authenticated) {
		return this.routing.initial(authenticated, this::load).flatMap(this.proxy::getServer);
	}

	public Optional<RegisteredServer> chooseAuth() {
		return this.routing.chooseAuth(this::load).flatMap(this.proxy::getServer);
	}

	/** The first time a player arrives on a server: what to tell them, and the server learns where they stand. */
	public void arrived(Player player, boolean firstServer) {
		final PlayerSession session = this.connections.session(player);

		if (session == null || this.closing)
			return;

		this.core.announce(session);

		if (!firstServer)
			return;

		final String key = switch (session.state()) {
			case PENDING_LOGIN -> "prompt.login";
			case PENDING_REGISTER -> "prompt.register";
			case AUTHENTICATED -> session.method() == null ? null : switch (session.method()) {
				case PREMIUM -> "join.premium";
				case SESSION -> "join.session";
				default -> null;
			};
		};

		if (key != null)
			player.sendMessage(this.texts.render(key));
	}

	private Optional<Player> playerOf(PlayerSession session) {
		return this.proxy.getPlayer(session.uuid()).filter(player -> this.connections.session(player) == session);
	}

	/** After a player's state changed: take them where they belong now. Runs on a database thread. */
	private void stateChanged(PlayerSession session) {
		final Optional<Player> player = this.playerOf(session);

		if (player.isEmpty())
			return;

		final String current = player.get().getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);

		this.routing.moveAfterChange(session.authenticated(), current, this::load).flatMap(this.proxy::getServer).ifPresent(target -> player.get().createConnectionRequest(target).connect().thenAccept(result -> {
			if (!result.isSuccessful())
				this.logger.warning("Could not move " + session.username() + " to " + target.getServerInfo().getName() + ".");
		}));
	}

	/** A connection is over: close its session. Safe to call twice. */
	public void release(Player player) {
		final PlayerSession session = this.connections.unbind(player);

		if (session != null && !this.closing)
			this.core.auth().close(session);
	}

	/** Once a second: kick whoever ran out of time, close the sessions of connections that vanished without an event, and remind the ones who still have to log in. */
	private void tick() {
		if (this.closing)
			return;

		final boolean remind = ++this.ticks % 10 == 0;
		final Instant now = Instant.now();

		for (final PlayerSession session : this.core.sessions().expired(now))
			this.proxy.getPlayer(session.uuid()).ifPresent(player -> player.disconnect(this.texts.render("kick.timeout")));

		for (final Map.Entry<Player, PlayerSession> entry : this.connections.all()) {
			final Player player = entry.getKey();
			final PlayerSession session = entry.getValue();

			if (!player.isActive()) {
				this.release(player);

			} else if (remind && session.state() != AuthState.AUTHENTICATED && player.getCurrentServer().isPresent()) {
				player.sendMessage(this.texts.render(session.state() == AuthState.PENDING_LOGIN ? "prompt.login" : "prompt.register"));
			}
		}
	}

	public Messages messages() {
		return this.core.messages();
	}

	@Override
	public void close() {
		this.closing = true;

		for (final ScheduledTask task : this.tasks)
			task.cancel();

		this.proxy.getEventManager().unregisterListeners(this.plugin);
		this.core.close();
	}
}
