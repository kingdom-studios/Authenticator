package pl.kingdomcraft.authenticator.common.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.PASSWORD;
import static pl.kingdomcraft.authenticator.common.support.AuthFixture.waitFor;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.auth.StateWatcher;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChangedMessage;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport;
import pl.kingdomcraft.authenticator.common.bridge.Recipient;
import pl.kingdomcraft.authenticator.common.bridge.RedisTransport;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;
import pl.kingdomcraft.authenticator.common.message.AuthCommands.Command;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;
import pl.kingdomcraft.authenticator.common.messaging.HmacSigner;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;
import pl.kingdomcraft.authenticator.common.messaging.RedisBus;
import pl.kingdomcraft.authenticator.common.messaging.RedisConfig;
import pl.kingdomcraft.authenticator.common.messaging.RedisEndpoint;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;
import pl.kingdomcraft.authenticator.common.support.TestRedis;

/** The proxy without Velocity: settings in, a working login machinery and bridge out, on a real database. */
class ProxyCoreIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("test");
	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Clock CLOCK = Clock.systemUTC();

	/** A carrier that records what would be sent over a player's connection. */
	static final class FakeCarrier extends PluginMessagingTransport {

		record Sent(Recipient recipient, String text) {
		}

		final List<Sent> sent = new CopyOnWriteArrayList<>();
		volatile boolean connected = true;

		FakeCarrier(EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger) {
			super(verifier, nodeId, clock, logger);
		}

		@Override
		protected boolean deliver(Recipient recipient, byte[] data) {
			this.sent.add(new Sent(recipient, new String(data, java.nio.charset.StandardCharsets.UTF_8)));

			return this.connected;
		}
	}

	private final List<String> events = new CopyOnWriteArrayList<>();
	private final List<String> moved = new CopyOnWriteArrayList<>();
	private final List<ProxyCore> cores = new ArrayList<>();
	private TestMariaDb.Schema schema;
	private TestMariaDb.Server server;
	private FakeCarrier carrier;

	@BeforeEach
	void setUp() throws Exception {
		this.server = TestMariaDb.requireServer();
		this.schema = TestMariaDb.createSchema(false);
	}

	@AfterEach
	void tearDown() throws Exception {
		for (final ProxyCore core : this.cores)
			core.close();

		if (this.schema != null)
			this.schema.close();
	}

	private Map<String, Object> config(String mode, boolean dryRun) {
		final Map<String, Object> root = new LinkedHashMap<>();

		root.put("node-id", "velocity-1");
		root.put("language", "en");
		root.put("dry-run", dryRun);
		root.put("database", Map.of("use", "private", "name", this.schema.name(), "pool-size", 4,
				"endpoints", Map.of("private", Map.of("host", this.server.host(), "port", this.server.port(), "user", this.server.user(), "password", this.server.password(), "ssl-mode", "disable"))));
		root.put("bridge", Map.of("mode", mode, "secret", SECRET, "database-poll-seconds", 0));
		root.put("routing", Map.of("auth-servers", List.of("auth"), "lobby-servers", List.of("lobby")));
		root.put("passwords", Map.of("bcrypt-cost", 4, "threads", 2));
		root.put("limits", Map.of("connections-per-ip", 1000));

		return root;
	}

	private ProxyCore start(Map<String, Object> config) throws Exception {
		final ProxyCore core = ProxyCore.start(ProxySettings.parse(config), CLOCK, LOGGER, (verifier, node, clock, logger) -> this.carrier = new FakeCarrier(verifier, node, clock, logger), List.of());

		this.cores.add(core);
		core.activate(new StateWatcher.Handler() {
			@Override
			public void accountRemoved(PlayerSession session) {
				ProxyCoreIntegrationTest.this.events.add("removed:" + session.username());
			}

			@Override
			public void typeChanged(PlayerSession session) {
				ProxyCoreIntegrationTest.this.events.add("type:" + session.username());
			}

			@Override
			public void stateForced(PlayerSession session) {
				ProxyCoreIntegrationTest.this.events.add("forced:" + session.username());
			}
		}, session -> this.moved.add(session.username() + ":" + session.state()));

		return core;
	}

	private int count(String sql) throws Exception {
		return this.schema.database().query(connection -> {
			try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
				result.next();

				return result.getInt(1);
			}
		});
	}

	private static PlayerSession join(ProxyCore core, String name) throws Exception {
		return core.auth().open(name, UUID.randomUUID(), InetAddress.getByName("203.0.113.7"), false).session();
	}

	private static EnvelopeVerifier independentVerifier() {
		return new EnvelopeVerifier(new HmacSigner(SECRET), Duration.ofSeconds(30), CLOCK, LOGGER);
	}

	private static byte[] signed(String type, String node, JsonObject payload) {
		final EnvelopeVerifier verifier = independentVerifier();

		return verifier.sign(Envelope.create(type, node, payload, CLOCK)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	@Test
	void startingAppliesTheSchemaAndBuildsAWorkingLoginMachinery() throws Exception {
		final ProxyCore core = this.start(this.config("database-only", false));
		final PlayerSession session = join(core, "Steve");

		assertEquals(AuthState.PENDING_REGISTER, session.state());
		assertEquals("register.ok", core.commands().run(session, Command.REGISTER, List.of(PASSWORD, PASSWORD)).get(10, TimeUnit.SECONDS).key());
		assertTrue(session.authenticated());
		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_accounts"));
		assertEquals(BridgeMode.DATABASE_ONLY, core.transport().mode());
		assertEquals(List.of("Steve:PENDING_REGISTER", "Steve:AUTHENTICATED"), this.moved, "the platform is told of every change, after the bridge");
	}

	@Test
	void startingRemovesTheStatesThisProxyLeftBehindAndKeepsOthers() throws Exception {
		this.start(this.config("database-only", false)).close();

		final UUID mine = UUID.randomUUID();
		final UUID theirs = UUID.randomUUID();

		this.schema.database().query(connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("INSERT INTO auth_presence (uuid, state, epoch, proxy_id) VALUES ('" + mine + "', 'PENDING_REGISTER', 1, 'velocity-1'), ('" + theirs + "', 'PENDING_REGISTER', 1, 'velocity-2')");
			}

			return null;
		});

		this.start(this.config("database-only", false));

		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_presence"));
		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_presence WHERE proxy_id = 'velocity-2'"));
	}

	@Test
	void shuttingDownRemovesTheStatesOfTheProxy() throws Exception {
		final ProxyCore core = this.start(this.config("database-only", false));

		core.auth().register(join(core, "Steve"), PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);
		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_presence"));

		core.close();

		assertEquals(0, this.count("SELECT COUNT(*) FROM auth_presence"));
		core.close(); // twice is harmless
	}

	// ---- plugin messaging ----

	@Test
	void aChangedStateIsSentSignedToTheServerOfThePlayer() throws Exception {
		final ProxyCore core = this.start(this.config("plugin-messaging", false));
		final PlayerSession session = join(core, "Steve");

		assertEquals(BridgeMode.PLUGIN_MESSAGING, core.transport().mode());

		core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);

		assertEquals(2, this.carrier.sent.size(), "one when the player joined and had to register, one when they had");

		final Envelope joined = independentVerifier().accept(this.carrier.sent.get(0).text(), "test").orElseThrow();

		assertEquals(new AuthStateMessage(session.uuid(), AuthState.PENDING_REGISTER, null, 1), BridgeMessages.authState(joined).orElseThrow());

		final FakeCarrier.Sent sent = this.carrier.sent.get(1);

		assertEquals(Recipient.serverOf(session.uuid()), sent.recipient());

		final Envelope envelope = independentVerifier().accept(sent.text(), "test").orElseThrow();

		assertEquals(MessageTypes.AUTH_STATE, envelope.type());
		assertEquals("velocity-1", envelope.node());
		assertEquals(new AuthStateMessage(session.uuid(), AuthState.AUTHENTICATED, AuthMethod.PASSWORD, session.epoch()), BridgeMessages.authState(envelope).orElseThrow());
	}

	@Test
	void theMessageCarriesTheEpochThatIsInTheDatabase() throws Exception {
		final ProxyCore core = this.start(this.config("plugin-messaging", false));
		final PlayerSession session = join(core, "Steve");

		core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);

		final Envelope envelope = independentVerifier().accept(this.carrier.sent.get(this.carrier.sent.size() - 1).text(), "test").orElseThrow();
		final long epoch = BridgeMessages.authState(envelope).orElseThrow().epoch();

		assertEquals(epoch, core.auth().sessions().find(session.uuid()).orElseThrow().epoch());
		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_presence WHERE epoch = " + epoch));
	}

	@Test
	void aSignedAccountChangedMessageMakesTheProxyLookAtThatPlayerAtOnce() throws Exception {
		final ProxyCore core = this.start(this.config("plugin-messaging", false));
		final PlayerSession session = join(core, "Steve");

		core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);
		core.auth().sessions().find(session.uuid()).orElseThrow();
		this.schema.database().query(connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("DELETE FROM auth_accounts");
			}

			return null;
		});

		final JsonObject payload = BridgeMessages.payload(new AccountChangedMessage(session.uuid(), AccountChange.REMOVED));

		assertTrue(this.carrier.receive(PluginMessagingTransport.Origin.TRUSTED_PEER, signed(MessageTypes.ACCOUNT_CHANGED, "paper-auth-1", payload)));
		assertTrue(waitFor(() -> this.events.contains("removed:Steve"), 5_000), this.events.toString());
		assertEquals(AuthState.PENDING_REGISTER, session.state());
	}

	@Test
	void aMessageFromAPlayersClientNeverReachesTheProxysLogic() throws Exception {
		final ProxyCore core = this.start(this.config("plugin-messaging", false));
		final PlayerSession session = join(core, "Steve");

		core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);
		this.schema.database().query(connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("DELETE FROM auth_accounts");
			}

			return null;
		});

		// Even a correctly signed message (a client cannot make one, but suppose it learned the secret) is refused when it comes over a player's connection.
		final JsonObject payload = BridgeMessages.payload(new AccountChangedMessage(session.uuid(), AccountChange.REMOVED));

		assertFalse(this.carrier.receive(PluginMessagingTransport.Origin.CLIENT, signed(MessageTypes.ACCOUNT_CHANGED, "paper-auth-1", payload)));
		Thread.sleep(400);

		assertEquals(List.of(), this.events);
		assertEquals(1, this.carrier.rejectedClientMessages());
		assertTrue(core.status().stream().anyMatch(line -> line.contains("1 from players' clients")), core.status().toString());
	}

	@Test
	void aMessageWithTheWrongSecretOrTheWrongDirectionChangesNothing() throws Exception {
		final ProxyCore core = this.start(this.config("plugin-messaging", false));
		final PlayerSession session = join(core, "Steve");

		core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);
		this.schema.database().query(connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("DELETE FROM auth_accounts");
			}

			return null;
		});

		final JsonObject payload = BridgeMessages.payload(new AccountChangedMessage(session.uuid(), AccountChange.REMOVED));
		final byte[] wrongSecret = new EnvelopeVerifier(new HmacSigner("another-secret-another-secret"), Duration.ofSeconds(30), CLOCK, LOGGER)
				.sign(Envelope.create(MessageTypes.ACCOUNT_CHANGED, "paper-auth-1", payload, CLOCK)).getBytes(java.nio.charset.StandardCharsets.UTF_8);

		assertFalse(this.carrier.receive(PluginMessagingTransport.Origin.TRUSTED_PEER, wrongSecret));

		// A validly signed AUTH_STATE is a message for backend servers; if a backend sent one to the proxy it would be nothing to act on.
		final JsonObject state = BridgeMessages.payload(new AuthStateMessage(session.uuid(), AuthState.AUTHENTICATED, AuthMethod.FORCED, 99));

		assertTrue(this.carrier.receive(PluginMessagingTransport.Origin.TRUSTED_PEER, signed(MessageTypes.AUTH_STATE, "paper-auth-1", state)));
		Thread.sleep(400);

		assertEquals(List.of(), this.events);
		assertTrue(session.authenticated(), "the account is gone in the database, but only the database or a real prompt may say so");
		assertEquals(0L, core.watcher().counters().get("accounts-removed"), "no round ran");
	}

	@Test
	void aSendFailsQuietlyWhenThereIsNoConnectionToCarryIt() throws Exception {
		final ProxyCore core = this.start(this.config("plugin-messaging", false));
		final PlayerSession session = join(core, "Steve");

		this.carrier.connected = false;

		assertTrue(core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS).ok(), "the login does not depend on the message");
		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_presence WHERE state = 'AUTHENTICATED'"), "it is in the database for the server to read");
	}

	// ---- polling ----

	@Test
	void aRoundOfPollingFindsWhatChangedInTheDatabase() throws Exception {
		final ProxyCore core = this.start(this.config("database-only", false));
		final PlayerSession session = join(core, "Steve");

		core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);
		this.schema.database().query(connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("DELETE FROM auth_accounts");
			}

			return null;
		});

		core.poll();

		assertEquals(List.of("removed:Steve"), this.events);
	}

	// ---- shadow mode ----

	@Test
	void shadowModeSendsNothingChangesNothingAndSaysSo() throws Exception {
		this.start(this.config("database-only", false)).close(); // creates the schema and leaves it empty

		this.schema.database().query(connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("INSERT INTO auth_presence (uuid, state, epoch, proxy_id) VALUES ('" + UUID.randomUUID() + "', 'PENDING_REGISTER', 1, 'velocity-1')");
			}

			return null;
		});

		final ProxyCore shadow = this.start(this.config("plugin-messaging", true));

		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_presence"), "shadow mode did not remove the row at start");
		assertEquals(BridgeMode.DATABASE_ONLY, shadow.transport().mode(), "no bridge in shadow mode, whatever the setting");
		assertEquals(null, this.carrier, "no carrier was even created");

		shadow.poll();
		shadow.maintain();

		assertTrue(shadow.status().get(0).contains("SHADOW MODE"), shadow.status().toString());
		assertTrue(shadow.status().stream().anyMatch(line -> line.startsWith("Shadow comparison:")), shadow.status().toString());

		shadow.close();
		this.cores.remove(shadow);

		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_presence"), "nor on shutdown");
	}

	@Test
	void theStatusReportsWhatIsGoingOn() throws Exception {
		final ProxyCore core = this.start(this.config("plugin-messaging", false));

		join(core, "Steve");

		final List<String> status = core.status();

		assertTrue(status.get(0).startsWith("Node velocity-1"), status.toString());
		assertTrue(status.stream().anyMatch(line -> line.startsWith("Database: private endpoint")), status.toString());
		assertTrue(status.stream().anyMatch(line -> line.startsWith("Bridge: plugin-messaging")), status.toString());
		assertTrue(status.stream().anyMatch(line -> line.equals("Players online: 1 (0 logged in, 0 logging in, 1 registering)")), status.toString());
	}

	@Test
	void aBadSettingStopsStartupAndLeavesNothingOpen() {
		final Map<String, Object> broken = this.config("database-only", false);

		broken.put("database", Map.of("use", "private", "name", this.schema.name(), "endpoints", Map.of("private", Map.of("host", "127.0.0.1", "port", 1, "user", "nobody", "password", "x", "ssl-mode", "disable"))));

		assertThrows(java.sql.SQLException.class, () -> ProxyCore.start(ProxySettings.parse(broken), CLOCK, LOGGER, null, List.of()));
	}

	private static <T extends Throwable> void assertThrows(Class<T> type, org.junit.jupiter.api.function.Executable executable) {
		org.junit.jupiter.api.Assertions.assertThrows(type, executable);
	}

	// ---- redis ----

	@Test
	void inRedisModeTheStateGoesOverTheStateChannel() throws Exception {
		assumeTrue(TestRedis.binaryAvailable(), "redis-server is not installed");

		try (TestRedis redis = TestRedis.start()) {
			final String prefix = "core-" + UUID.randomUUID().toString().substring(0, 8) + ":";
			final Map<String, Object> config = this.config("redis", false);

			config.put("redis", Map.of("use", "private", "prefix", prefix, "endpoints", Map.of("private", Map.of("host", "127.0.0.1", "port", redis.port(), "ssl", false, "connect-timeout-ms", 1000))));

			final RedisConfig backendConfig = new RedisConfig(new ZonedEndpoints<>(Zone.PRIVATE, List.of(), Map.of(Zone.PRIVATE, new RedisEndpoint("127.0.0.1", redis.port(), "", "", false, true, "", "", 0, 1_000))), prefix);
			final RedisTransport backend = new RedisTransport(new RedisBus(backendConfig, independentVerifier(), "paper-1", CLOCK, LOGGER), RedisTransport.Role.BACKEND, LOGGER);
			final BlockingQueue<Envelope> atBackend = new LinkedBlockingQueue<>();

			try {
				backend.listen(atBackend::add);
				backend.start();
				assertTrue(waitFor(backend::isConnected, 10_000));

				final ProxyCore core = this.start(config);

				assertEquals(BridgeMode.REDIS, core.transport().mode());
				assertTrue(waitFor(core.transport()::isConnected, 10_000));

				final PlayerSession session = join(core, "Steve");

				core.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS);

				final Envelope joined = atBackend.poll(5, TimeUnit.SECONDS);

				assertNotNull(joined, "no message arrived");
				assertEquals(AuthState.PENDING_REGISTER, BridgeMessages.authState(joined).orElseThrow().state());

				final Envelope envelope = atBackend.poll(5, TimeUnit.SECONDS);

				assertNotNull(envelope, "no second message arrived");
				assertEquals(new AuthStateMessage(session.uuid(), AuthState.AUTHENTICATED, AuthMethod.PASSWORD, session.epoch()), BridgeMessages.authState(envelope).orElseThrow());

				// and an admin message from the backend side reaches the proxy's watcher
				this.schema.database().query(connection -> {
					try (var statement = connection.createStatement()) {
						statement.execute("DELETE FROM auth_accounts");
					}

					return null;
				});

				backend.send(backend.newEnvelope(MessageTypes.ACCOUNT_CHANGED, BridgeMessages.payload(new AccountChangedMessage(session.uuid(), AccountChange.REMOVED))), Recipient.proxyVia(session.uuid()));

				assertTrue(waitFor(() -> this.events.contains("removed:Steve"), 5_000), this.events.toString());

			} finally {
				backend.close();
			}
		}
	}
}
