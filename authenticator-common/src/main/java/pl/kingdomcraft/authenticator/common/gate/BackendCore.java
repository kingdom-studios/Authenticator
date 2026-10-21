package pl.kingdomcraft.authenticator.common.gate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.admin.AdminService;
import pl.kingdomcraft.authenticator.common.auth.PasswordPolicy;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChangedMessage;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.bridge.BridgeTransport;
import pl.kingdomcraft.authenticator.common.bridge.NullTransport;
import pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport;
import pl.kingdomcraft.authenticator.common.bridge.Recipient;
import pl.kingdomcraft.authenticator.common.bridge.RedisTransport;
import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.database.Migrator;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;
import pl.kingdomcraft.authenticator.common.messaging.RedisBus;
import pl.kingdomcraft.authenticator.common.migrate.MineLoginImporter;
import pl.kingdomcraft.authenticator.common.premium.HttpPremiumApi;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.repository.PremiumCacheRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository;
import pl.kingdomcraft.authenticator.common.repository.SessionRepository;
import pl.kingdomcraft.authenticator.common.settings.BackendSettings;

/**
 * Everything a backend server does that does not depend on Paper: the database (which it only reads and writes rows in, never migrates),
 * the bridge to the proxy, the verification of players who join, and the admin actions. The Paper plugin adds the gate over events, the
 * commands and the placeholders, so all of this is tested on a real database without a server.
 *
 * <p>The {@link Gate} is created by the caller before this exists: from the moment the plugin loads, nobody passes, and the core only
 * opens it for players the proxy has logged in.
 */
public final class BackendCore implements AutoCloseable {

	/** Creates the PluginMessaging transport; the platform knows how to move bytes over its connections. */
	@FunctionalInterface
	public interface PluginMessagingFactory {
		PluginMessagingTransport create(EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger);
	}

	private final BackendSettings settings;
	private final JoinVerifier.Role role;
	private final Logger logger;
	private final Database database;
	private final AccountRepository accounts;
	private final PresenceRepository presence;
	private final JoinVerifier verifier;
	private final AdminService admin;
	private final MineLoginImporter importer;
	private final AccountSnapshots snapshots;
	private final BridgeTransport transport;
	private final EnvelopeVerifier envelopeVerifier;

	private BackendCore(BackendSettings settings, JoinVerifier.Role role, Logger logger, Database database, AccountRepository accounts, PresenceRepository presence, JoinVerifier verifier, AdminService admin,
			MineLoginImporter importer, AccountSnapshots snapshots, BridgeTransport transport, EnvelopeVerifier envelopeVerifier) {
		this.settings = settings;
		this.role = role;
		this.logger = logger;
		this.database = database;
		this.accounts = accounts;
		this.presence = presence;
		this.verifier = verifier;
		this.admin = admin;
		this.importer = importer;
		this.snapshots = snapshots;
		this.transport = transport;
		this.envelopeVerifier = envelopeVerifier;
	}

	/**
	 * Connects, checks that the schema is the one this build needs (it never changes it: the proxy does) and builds everything. Nothing
	 * listens yet: see {@link #activate}. Blocking.
	 *
	 * @param poll    how often the database is read for the players on this server
	 * @param lookups the services that say whether a name is an original account; null for the ones in the settings (tests pass their own)
	 * @throws SQLException          if the database cannot be reached
	 * @throws IllegalStateException if the schema is older (the proxy has not migrated yet) or newer (this build is outdated)
	 */
	public static BackendCore start(BackendSettings settings, JoinVerifier.Role role, Duration poll, Gate gate, JoinVerifier.Listener listener, Clock clock, Logger logger, PluginMessagingFactory pluginMessaging,
			List<PremiumApi> lookups) throws SQLException {
		final Database database = Database.connect(settings.database(), logger);
		BridgeTransport transport = null;
		JoinVerifier verifier = null;

		try {
			logger.info("Database schema is at version " + new Migrator(database, logger).verify() + ".");

			final AccountRepository accounts = new AccountRepository(database);
			final SessionRepository ipSessions = new SessionRepository(database);
			final PresenceRepository presence = new PresenceRepository(database);
			final AuditRepository audit = new AuditRepository(database);
			final PremiumCacheRepository premiumCache = new PremiumCacheRepository(database);

			final List<PremiumApi> apis = lookups != null ? lookups : HttpPremiumApi.create(settings.premium().apis(), HttpPremiumApi.newClient(settings.premium().timeout()), settings.premium().timeout());
			final PremiumResolver resolver = new PremiumResolver(apis, premiumCache, settings.premium().timing(), clock, logger);
			final AdminService admin = new AdminService(accounts, ipSessions, presence, audit, new PasswordHasher(settings.passwords().hasher()),
					new PasswordPolicy(settings.passwords().minLength(), settings.passwords().forbidden()), resolver, settings.nodeId(), logger);
			final MineLoginImporter importer = new MineLoginImporter(database, accounts, audit, settings.minelogin(), clock, logger, settings.nodeId());
			final EnvelopeVerifier envelopeVerifier = settings.bridge().verifier(clock, logger);

			transport = createTransport(settings, clock, logger, envelopeVerifier, pluginMessaging);
			verifier = new JoinVerifier(presence, gate, role, poll, listener, logger);

			return new BackendCore(settings, role, logger, database, accounts, presence, verifier, admin, importer, new AccountSnapshots(accounts, database.executor(), logger), transport, envelopeVerifier);

		} catch (final SQLException | RuntimeException | Error ex) {
			if (verifier != null)
				verifier.close();

			if (transport != null)
				transport.close();

			database.close();

			throw ex;
		}
	}

	private static BridgeTransport createTransport(BackendSettings settings, Clock clock, Logger logger, EnvelopeVerifier verifier, PluginMessagingFactory pluginMessaging) {
		return switch (settings.bridge().mode()) {
			case PLUGIN_MESSAGING -> pluginMessaging.create(verifier, settings.nodeId(), clock, logger);
			case REDIS -> new RedisTransport(new RedisBus(settings.bridge().redis(), verifier, settings.nodeId(), clock, logger), RedisTransport.Role.BACKEND, logger);
			case DATABASE_ONLY -> new NullTransport(settings.nodeId(), clock);
		};
	}

	/** Starts listening to the proxy. Call once, after the platform is ready for messages. */
	public void activate() {
		this.transport.listen(envelope -> BridgeMessages.authState(envelope).ifPresent(this.verifier::apply));
		this.transport.start();
	}

	/**
	 * Tells the proxy an account was changed here, so that it looks at once instead of at its next round. Best effort: the change is in the
	 * database either way.
	 *
	 * @return whether a message was sent
	 */
	public boolean notifyProxy(UUID player, AccountChange change) {
		final JsonObject payload = BridgeMessages.payload(new AccountChangedMessage(player, change));

		return this.transport.send(this.transport.newEnvelope(MessageTypes.ACCOUNT_CHANGED, payload), Recipient.proxyVia(player));
	}

	public BackendSettings settings() {
		return this.settings;
	}

	public JoinVerifier verifier() {
		return this.verifier;
	}

	public AdminService admin() {
		return this.admin;
	}

	public MineLoginImporter importer() {
		return this.importer;
	}

	public AccountSnapshots snapshots() {
		return this.snapshots;
	}

	public BridgeTransport transport() {
		return this.transport;
	}

	public Database database() {
		return this.database;
	}

	public AccountRepository accounts() {
		return this.accounts;
	}

	public PresenceRepository presence() {
		return this.presence;
	}

	/** Lines for {@code /authadmin status}. */
	public List<String> status() {
		final List<String> lines = new ArrayList<>();

		lines.add("Node " + this.settings.nodeId() + ", role " + this.role);
		lines.add("Database: " + this.database.zone().key() + " endpoint " + this.database.endpoint().address());
		lines.add("Bridge: " + this.settings.bridge().mode().key() + (this.settings.bridge().mode() == BridgeMode.DATABASE_ONLY || this.transport.isConnected() ? "" : " (NOT connected: the database is read instead)"));

		if (this.settings.bridge().mode() != BridgeMode.DATABASE_ONLY)
			lines.add("Bridge messages refused: " + this.envelopeVerifier.rejectedSignatures() + " bad signature, " + this.envelopeVerifier.rejectedStale() + " stale or replayed, " + this.envelopeVerifier.rejectedMalformed()
					+ " malformed");

		lines.add("Players known to the gate: " + this.verifier.gate().size() + " (" + this.verifier.gate().authenticatedCount() + " logged in)");

		return lines;
	}

	@Override
	public void close() {
		this.verifier.close();
		this.transport.close();
		this.database.close();
	}
}
