package pl.kingdomcraft.authenticator.common.proxy;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.auth.AuthService;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.auth.Maintenance;
import pl.kingdomcraft.authenticator.common.auth.PasswordPolicy;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider;
import pl.kingdomcraft.authenticator.common.auth.SessionManager;
import pl.kingdomcraft.authenticator.common.auth.StateListener;
import pl.kingdomcraft.authenticator.common.auth.StateWatcher;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.bridge.BridgeTransport;
import pl.kingdomcraft.authenticator.common.bridge.NullTransport;
import pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport;
import pl.kingdomcraft.authenticator.common.bridge.Recipient;
import pl.kingdomcraft.authenticator.common.bridge.RedisTransport;
import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Migrator;
import pl.kingdomcraft.authenticator.common.hash.HashPool;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;
import pl.kingdomcraft.authenticator.common.message.AuthCommands;
import pl.kingdomcraft.authenticator.common.message.Messages;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;
import pl.kingdomcraft.authenticator.common.messaging.RedisBus;
import pl.kingdomcraft.authenticator.common.migrate.MineLoginImporter;
import pl.kingdomcraft.authenticator.common.premium.HttpPremiumApi;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.repository.IpBlockRepository;
import pl.kingdomcraft.authenticator.common.repository.PremiumCacheRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository;
import pl.kingdomcraft.authenticator.common.repository.SessionRepository;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;
import pl.kingdomcraft.authenticator.common.shadow.ShadowComparator;

/**
 * Everything the proxy does that does not depend on Velocity, assembled from the settings: the database, the login machinery, the bridge
 * to the backend servers, the watcher for changes made behind the proxy's back. The Velocity plugin only adds what needs Velocity (events,
 * commands, connections), so all of this is tested against a real database without a proxy.
 */
public final class ProxyCore implements AutoCloseable {

	/** Creates the PluginMessaging transport; the platform knows how to move bytes over its connections. */
	@FunctionalInterface
	public interface PluginMessagingFactory {
		PluginMessagingTransport create(EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger);
	}

	private final ProxySettings settings;
	private final Clock clock;
	private final Logger logger;
	private final Messages messages;
	private final Database database;
	private final AccountRepository accounts;
	private final PresenceRepository presence;
	private final HashPool hashPool;
	private final SessionManager sessions;
	private final PreLoginDecider decider;
	private final AuthService auth;
	private final AuthCommands commands;
	private final Maintenance maintenance;
	private final MineLoginImporter importer;
	private final ShadowComparator shadow;
	private final BridgeTransport transport;
	private final EnvelopeVerifier verifier;

	private final AtomicBoolean polling = new AtomicBoolean();
	private final AtomicBoolean maintaining = new AtomicBoolean();
	private volatile StateWatcher watcher;
	private volatile StateListener platformListener = session -> {
	};
	private volatile boolean closed;

	private ProxyCore(ProxySettings settings, Clock clock, Logger logger, Messages messages, Database database, AccountRepository accounts, PresenceRepository presence, HashPool hashPool, SessionManager sessions,
			PreLoginDecider decider, AuthService auth, Maintenance maintenance, MineLoginImporter importer, ShadowComparator shadow, BridgeTransport transport, EnvelopeVerifier verifier) {
		this.settings = settings;
		this.clock = clock;
		this.logger = logger;
		this.messages = messages;
		this.database = database;
		this.accounts = accounts;
		this.presence = presence;
		this.hashPool = hashPool;
		this.sessions = sessions;
		this.decider = decider;
		this.auth = auth;
		this.commands = new AuthCommands(auth);
		this.maintenance = maintenance;
		this.importer = importer;
		this.shadow = shadow;
		this.transport = transport;
		this.verifier = verifier;
	}

	/**
	 * Connects, migrates the schema and builds everything. Nothing is started yet: see {@link #activate}.
	 *
	 * @param pluginMessaging used when the bridge mode is {@code plugin-messaging}
	 * @param lookups         the services that say whether a name is an original account; null for the ones in the settings (tests pass their own)
	 * @throws SQLException if the database cannot be reached or migrated; nothing stays open
	 */
	public static ProxyCore start(ProxySettings settings, Clock clock, Logger logger, PluginMessagingFactory pluginMessaging, List<PremiumApi> lookups) throws SQLException {
		final Messages messages = Messages.load(settings.language(), settings.messages());
		final Database database = Database.connect(settings.database(), logger);
		HashPool hashPool = null;
		BridgeTransport transport = null;

		try {
			final int schema = new Migrator(database, logger).migrate();

			logger.info("Database schema is at version " + schema + ".");

			final AccountRepository accounts = new AccountRepository(database);
			final SessionRepository ipSessions = new SessionRepository(database);
			final PresenceRepository presence = new PresenceRepository(database);
			final AuditRepository audit = new AuditRepository(database);
			final IpBlockRepository blocks = new IpBlockRepository(database);
			final PremiumCacheRepository premiumCache = new PremiumCacheRepository(database);

			if (!settings.dryRun()) {
				final int stale = presence.deleteOwnedBy(settings.nodeId());

				if (stale > 0)
					logger.info("Removed " + stale + " login states this proxy left behind when it last stopped.");
			}

			hashPool = new HashPool(settings.passwords().threads(), settings.passwords().queueSize());

			final List<PremiumApi> apis = lookups != null ? lookups : HttpPremiumApi.create(settings.premium().apis(), HttpPremiumApi.newClient(settings.premium().timeout()), settings.premium().timeout());
			final PremiumResolver resolver = new PremiumResolver(apis, premiumCache, settings.premium().timing(), clock, logger);
			final SessionManager sessions = new SessionManager();
			final PreLoginDecider decider = new PreLoginDecider(settings.login(), settings.limits(), settings.premium(), accounts, blocks, resolver, sessions, clock, logger);
			final AuthService auth = new AuthService(database, accounts, ipSessions, presence, audit, blocks, new PasswordHasher(settings.passwords().hasher()), hashPool,
					new PasswordPolicy(settings.passwords().minLength(), settings.passwords().forbidden()), settings.login(), settings.limits(), sessions, decider, resolver, clock, settings.nodeId(), logger);
			final Maintenance maintenance = new Maintenance(ipSessions, blocks, premiumCache, audit, settings.auditRetentionDays(), clock, logger);
			final MineLoginImporter importer = new MineLoginImporter(database, accounts, audit, settings.minelogin(), clock, logger, settings.nodeId());
			final ShadowComparator shadow = new ShadowComparator(clock, logger::info);
			final EnvelopeVerifier verifier = settings.bridge().verifier(clock, logger);

			transport = createTransport(settings, clock, logger, verifier, pluginMessaging);

			return new ProxyCore(settings, clock, logger, messages, database, accounts, presence, hashPool, sessions, decider, auth, maintenance, importer, shadow, transport, verifier);

		} catch (final SQLException | RuntimeException | Error ex) {
			if (transport != null)
				transport.close();

			if (hashPool != null)
				hashPool.close();

			database.close();

			throw ex;
		}
	}

	private static BridgeTransport createTransport(ProxySettings settings, Clock clock, Logger logger, EnvelopeVerifier verifier, PluginMessagingFactory pluginMessaging) {
		// Shadow mode changes nothing on any connection, so it must not talk to the servers either.
		if (settings.dryRun())
			return new NullTransport(settings.nodeId(), clock);

		return switch (settings.bridge().mode()) {
			case PLUGIN_MESSAGING -> pluginMessaging.create(verifier, settings.nodeId(), clock, logger);
			case REDIS -> new RedisTransport(new RedisBus(settings.bridge().redis(), verifier, settings.nodeId(), clock, logger), RedisTransport.Role.PROXY, logger);
			case DATABASE_ONLY -> new NullTransport(settings.nodeId(), clock);
		};
	}

	/**
	 * Starts what listens and reacts. Call it once, after the platform is ready for events.
	 *
	 * @param handler  told about what the watcher found; the platform kicks and moves players
	 * @param listener told after the bridge message about a changed state has been sent; the platform moves the player
	 */
	public void activate(StateWatcher.Handler handler, StateListener listener) {
		this.platformListener = listener;
		this.watcher = new StateWatcher(this.auth, this.accounts, this.presence, this.sessions, this.settings.nodeId(), handler, this.logger);

		if (this.settings.dryRun())
			return;

		this.auth.setStateListener(this::stateChanged);

		// A message is only a prompt to look at one player sooner; what is true is in the database.
		this.transport.listen(envelope -> BridgeMessages.accountChanged(envelope).ifPresent(message -> this.database.supplyAsync(() -> {
			this.watcher.check(message.player());

			return null;
		})));

		this.transport.start();
	}

	private void stateChanged(PlayerSession session) {
		this.announce(session);
		this.platformListener.stateChanged(session);
	}

	/**
	 * Tells the server the player is on how far they got, without writing anything: for a player who just arrived on a server. Best effort;
	 * a server that does not hear it reads the database.
	 */
	public void announce(PlayerSession session) {
		if (this.settings.dryRun() || this.closed)
			return;

		final JsonObject payload = BridgeMessages.payload(new AuthStateMessage(session.uuid(), session.state(), session.method(), session.epoch()));

		this.transport.send(this.transport.newEnvelope(MessageTypes.AUTH_STATE, payload), Recipient.serverOf(session.uuid()));
	}

	/** One round of looking for changes made behind the proxy's back. Blocking; a round that is still running is not started twice. */
	public void poll() {
		final StateWatcher current = this.watcher;

		if (current == null || this.closed || this.settings.dryRun() || !this.polling.compareAndSet(false, true))
			return;

		try {
			current.checkAll();

		} catch (final RuntimeException ex) {
			this.logger.log(Level.WARNING, "Looking for changes in the database failed", ex);

		} finally {
			this.polling.set(false);
		}
	}

	/** One round of cleaning up. Blocking. */
	public void maintain() {
		if (this.closed || this.settings.dryRun() || !this.maintaining.compareAndSet(false, true))
			return;

		try {
			final Maintenance.Report report = this.maintenance.runOnce();

			if (report.total() > 0)
				this.logger.fine("Maintenance removed " + report.total() + " entries (" + report + ").");

		} finally {
			this.maintaining.set(false);
		}
	}

	public ProxySettings settings() {
		return this.settings;
	}

	public Messages messages() {
		return this.messages;
	}

	public Database database() {
		return this.database;
	}

	public AuthService auth() {
		return this.auth;
	}

	public AuthCommands commands() {
		return this.commands;
	}

	public PreLoginDecider decider() {
		return this.decider;
	}

	public SessionManager sessions() {
		return this.sessions;
	}

	public MineLoginImporter importer() {
		return this.importer;
	}

	public ShadowComparator shadow() {
		return this.shadow;
	}

	public BridgeTransport transport() {
		return this.transport;
	}

	public StateWatcher watcher() {
		return this.watcher;
	}

	/** Lines for {@code /authadmin status}. */
	public List<String> status() {
		final List<String> lines = new ArrayList<>();
		final Map<AuthState, Integer> byState = new java.util.EnumMap<>(AuthState.class);

		for (final PlayerSession session : this.sessions.all())
			byState.merge(session.state(), 1, Integer::sum);

		lines.add("Node " + this.settings.nodeId() + (this.settings.dryRun() ? " (SHADOW MODE: only comparing, changing nothing)" : ""));
		lines.add("Database: " + this.database.zone().key() + " endpoint " + this.database.endpoint().address() + ", schema " + Migrator.latestVersion());
		lines.add("Bridge: " + this.settings.bridge().mode().key() + (this.settings.dryRun() ? " (off in shadow mode)" : this.transport.isConnected() ? "" : " (NOT connected: servers read the database)"));

		if (this.settings.bridge().mode() != BridgeMode.DATABASE_ONLY && !this.settings.dryRun())
			lines.add("Bridge messages refused: " + this.verifier.rejectedSignatures() + " bad signature, " + this.verifier.rejectedStale() + " stale or replayed, " + this.verifier.rejectedMalformed()
					+ " malformed" + (this.transport instanceof PluginMessagingTransport pmc ? ", " + pmc.rejectedClientMessages() + " from players' clients" : ""));

		lines.add("Players online: " + this.sessions.size() + " (" + byState.getOrDefault(AuthState.AUTHENTICATED, 0) + " logged in, " + byState.getOrDefault(AuthState.PENDING_LOGIN, 0) + " logging in, "
				+ byState.getOrDefault(AuthState.PENDING_REGISTER, 0) + " registering)");
		lines.add("Hashing: " + this.hashPool.queued() + " waiting");

		if (this.watcher != null && !this.settings.dryRun())
			lines.add("Changes noticed in the database: " + this.watcher.counters());

		if (this.settings.dryRun()) {
			final ShadowComparator.Stats stats = this.shadow.stats();

			lines.add("Shadow comparison: " + stats.agree() + " agree, " + stats.disagree() + " differ, " + stats.undecided() + " not comparable; UUIDs: " + stats.uuidAgree() + " agree, " + stats.uuidDisagree()
					+ " differ");

			final List<ShadowComparator.Disagreement> recent = this.shadow.recent();

			for (final ShadowComparator.Disagreement disagreement : recent.subList(Math.max(0, recent.size() - 5), recent.size()))
				lines.add("  " + disagreement);
		}

		return lines;
	}

	public Instant now() {
		return Instant.ofEpochMilli(this.clock.millis());
	}

	@Override
	public void close() {
		if (this.closed)
			return;

		this.closed = true;

		this.transport.close();

		if (!this.settings.dryRun())
			try {
				this.presence.deleteOwnedBy(this.settings.nodeId());

			} catch (final SQLException | RuntimeException ex) {
				this.logger.warning("Could not remove this proxy's login states on shutdown; they are removed when it starts again: " + ex.getMessage());
			}

		this.hashPool.close();
		this.database.close();
	}
}
