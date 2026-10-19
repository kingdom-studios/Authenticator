package pl.kingdomcraft.authenticator.common.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.admin.AdminService;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.auth.StateWatcher;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMode;
import pl.kingdomcraft.authenticator.common.gate.JoinVerifier.Role;
import pl.kingdomcraft.authenticator.common.proxy.ProxyCore;
import pl.kingdomcraft.authenticator.common.settings.BackendSettings;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;
import pl.kingdomcraft.authenticator.common.support.LinkedCarrier;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;
import pl.kingdomcraft.authenticator.common.support.TestRedis;

/** A proxy and a backend server on one database, talking through the real bridge code: what each side does about what the other says. */
class BackendCoreIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("test");
	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Clock CLOCK = Clock.systemUTC();

	private TestMariaDb.Server server;
	private TestMariaDb.Schema schema;
	private final Gate gate = new Gate();
	private final List<String> events = new CopyOnWriteArrayList<>();
	private final List<String> removed = new CopyOnWriteArrayList<>();
	private final List<AutoCloseable> toClose = new ArrayList<>();
	private LinkedCarrier proxyCarrier;
	private LinkedCarrier backendCarrier;

	@BeforeEach
	void setUp() throws Exception {
		this.server = TestMariaDb.requireServer();
		this.schema = TestMariaDb.createSchema(false);
	}

	@AfterEach
	void tearDown() throws Exception {
		for (int i = this.toClose.size() - 1; i >= 0; i--)
			this.toClose.get(i).close();

		if (this.schema != null)
			this.schema.close();
	}

	private Map<String, Object> database(String key) {
		return Map.of(key, Map.of("use", "private", "name", this.schema.name(), "pool-size", 4,
				"endpoints", Map.of("private", Map.of("host", this.server.host(), "port", this.server.port(), "user", this.server.user(), "password", this.server.password(), "ssl-mode", "disable"))));
	}

	private ProxyCore proxy(String mode) throws Exception {
		final Map<String, Object> root = new LinkedHashMap<>();

		root.put("node-id", "velocity-1");
		root.put("language", "en");
		root.putAll(this.database("database"));
		root.put("bridge", Map.of("mode", mode, "secret", SECRET, "database-poll-seconds", 0));
		root.put("routing", Map.of("auth-servers", List.of("auth"), "lobby-servers", List.of("lobby")));
		root.put("passwords", Map.of("bcrypt-cost", 4, "threads", 2));
		root.put("limits", Map.of("connections-per-ip", 1000));

		final ProxyCore core = ProxyCore.start(ProxySettings.parse(root), CLOCK, LOGGER, (verifier, node, clock, logger) -> this.proxyCarrier = new LinkedCarrier(verifier, node, clock, logger), List.of());

		this.toClose.add(core);
		core.activate(new StateWatcher.Handler() {
			@Override
			public void accountRemoved(PlayerSession session) {
				BackendCoreIntegrationTest.this.removed.add(session.username());
			}

			@Override
			public void typeChanged(PlayerSession session) {
				BackendCoreIntegrationTest.this.events.add("type:" + session.username());
			}

			@Override
			public void stateForced(PlayerSession session) {
				BackendCoreIntegrationTest.this.events.add("forced:" + session.username());
			}
		}, session -> {
		});

		return core;
	}

	private BackendSettings backendSettings(String mode) {
		final Map<String, Object> root = new LinkedHashMap<>();

		root.put("Node_Id", "paper-auth-1");
		root.putAll(this.database("Database"));
		root.put("Bridge", Map.of("Mode", mode, "Secret", SECRET));
		root.put("Passwords", Map.of("Bcrypt_Cost", 4));

		return BackendSettings.parse(root);
	}

	private BackendCore backend(String mode, Role role, Duration poll) throws Exception {
		final BackendCore core = BackendCore.start(this.backendSettings(mode), role, poll, this.gate, new JoinVerifier.Listener() {
			@Override
			public void authenticated(UUID player) {
				BackendCoreIntegrationTest.this.events.add("in:" + player);
			}

			@Override
			public void deauthenticated(UUID player) {
				BackendCoreIntegrationTest.this.events.add("out:" + player);
			}
		}, CLOCK, LOGGER, (verifier, node, clock, logger) -> this.backendCarrier = new LinkedCarrier(verifier, node, clock, logger), List.of());

		this.toClose.add(core);
		core.activate();

		return core;
	}

	private void link() {
		this.proxyCarrier.peer = this.backendCarrier;
		this.backendCarrier.peer = this.proxyCarrier;
	}

	private static PlayerSession join(ProxyCore proxy, String name) throws Exception {
		return proxy.auth().open(name, UUID.randomUUID(), InetAddress.getByName("203.0.113.7"), false).session();
	}

	private static void register(ProxyCore proxy, PlayerSession session) throws Exception {
		assertTrue(proxy.auth().register(session, PASSWORD, PASSWORD).get(10, TimeUnit.SECONDS).ok());
	}

	// ---- start ----

	@Test
	void aSchemaThatIsNotThereYetStopsTheStartAndLeavesNothingOpen() throws Exception {
		final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> this.backend("database-only", Role.GAME, Duration.ofSeconds(60)));

		assertTrue(ex.getMessage().contains("proxy"), "it tells the admin to start the proxy first: " + ex.getMessage());
		assertEquals(0, this.gate.size());
	}

	@Test
	void theBackendNeverChangesTheSchema() throws Exception {
		assertThrows(IllegalStateException.class, () -> this.backend("database-only", Role.GAME, Duration.ofSeconds(60)));

		final int tables = this.schema.database().query(connection -> {
			try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + this.schema.name() + "'")) {
				result.next();

				return result.getInt(1);
			}
		});

		assertEquals(0, tables);
	}

	// ---- the bridge in both directions ----

	@Test
	void aLoginOnTheProxyReachesTheBackendThroughTheBridgeAtOnce() throws Exception {
		final ProxyCore proxy = this.proxy("plugin-messaging");
		final BackendCore backend = this.backend("plugin-messaging", Role.AUTH, Duration.ofSeconds(60)); // the poll will not run: only the message can do it

		this.link();

		final PlayerSession session = join(proxy, "Steve");

		assertTrue(backend.verifier().checkLogin(session.uuid()).allowed());
		backend.verifier().joined(session.uuid());
		assertFalse(this.gate.authenticated(session.uuid()));

		register(proxy, session);

		assertTrue(waitFor(() -> this.gate.authenticated(session.uuid()), 5_000), "the signed message logged the player in");
		assertTrue(waitFor(() -> this.events.contains("in:" + session.uuid()), 5_000), this.events.toString());
	}

	@Test
	void theBackendLearnsOfALoginWithoutTheBridgeToo() throws Exception {
		final ProxyCore proxy = this.proxy("database-only");
		final BackendCore backend = this.backend("database-only", Role.AUTH, Duration.ofMillis(250));

		assertEquals(BridgeMode.DATABASE_ONLY, backend.transport().mode());

		final PlayerSession session = join(proxy, "Steve");

		backend.verifier().checkLogin(session.uuid());
		backend.verifier().joined(session.uuid());
		register(proxy, session);

		assertTrue(waitFor(() -> this.gate.authenticated(session.uuid()), 5_000), "the poll found it");
	}

	@Test
	void anAdminsForcedLogoutOnTheBackendReachesThePlayerAtOnce() throws Exception {
		final ProxyCore proxy = this.proxy("plugin-messaging");
		final BackendCore backend = this.backend("plugin-messaging", Role.GAME, Duration.ofSeconds(60));

		this.link();

		final PlayerSession session = join(proxy, "Steve");

		register(proxy, session);
		assertTrue(backend.verifier().checkLogin(session.uuid()).allowed());
		backend.verifier().joined(session.uuid());
		assertTrue(this.gate.authenticated(session.uuid()));

		final AdminService.Result result = backend.admin().forceLogout("Steve", "Admin");

		assertTrue(result.ok());
		assertTrue(backend.notifyProxy(result.player(), result.change()), "the message was carried");

		// backend -> proxy (look at the player) -> database row taken over -> proxy -> backend (state message)
		assertTrue(waitFor(() -> !proxy.auth().sessions().find(session.uuid()).orElseThrow().authenticated(), 5_000), "the proxy acted on the message");
		assertTrue(waitFor(() -> !this.gate.authenticated(session.uuid()), 5_000), "and told the backend, which froze the player");
		assertTrue(this.events.contains("out:" + session.uuid()), this.events.toString());
	}

	@Test
	void anAdminsRemovedAccountSendsThePlayerBackToRegistering() throws Exception {
		final ProxyCore proxy = this.proxy("plugin-messaging");
		final BackendCore backend = this.backend("plugin-messaging", Role.GAME, Duration.ofSeconds(60));

		this.link();

		final PlayerSession session = join(proxy, "Steve");

		register(proxy, session);
		backend.verifier().checkLogin(session.uuid());
		backend.verifier().joined(session.uuid());

		final AdminService.Result result = backend.admin().unregister("Steve", "Admin");

		backend.notifyProxy(result.player(), result.change());

		assertTrue(waitFor(() -> this.removed.contains("Steve"), 5_000), "the proxy noticed");
		assertTrue(waitFor(() -> !this.gate.authenticated(session.uuid()), 5_000), "and the backend froze the player");
	}

	@Test
	void withoutAMessageTheProxyNoticesWithinItsPoll() throws Exception {
		final ProxyCore proxy = this.proxy("database-only");
		final BackendCore backend = this.backend("database-only", Role.GAME, Duration.ofSeconds(60));

		final PlayerSession session = join(proxy, "Steve");

		register(proxy, session);
		backend.verifier().checkLogin(session.uuid());

		assertFalse(backend.notifyProxy(session.uuid(), AccountChange.REMOVED), "database-only sends nothing");
		backend.admin().unregister("Steve", "Admin");

		proxy.poll();

		assertEquals(List.of("Steve"), this.removed);
	}

	@Test
	void aMessageThatCannotBeCarriedIsNotAnError() throws Exception {
		final ProxyCore proxy = this.proxy("plugin-messaging");
		final BackendCore backend = this.backend("plugin-messaging", Role.GAME, Duration.ofSeconds(60));

		this.link();
		this.backendCarrier.connected = false; // nobody online on the server to carry it

		final PlayerSession session = join(proxy, "Steve");

		register(proxy, session);
		backend.admin().changePassword("Steve", "a-brand-new-one", "Admin");

		assertFalse(backend.notifyProxy(session.uuid(), AccountChange.PASSWORD));
		assertTrue(proxy.auth().sessions().find(session.uuid()).isPresent());
	}

	@Test
	void aMessageSignedWithAnotherSecretNeverReachesTheGate() throws Exception {
		final ProxyCore proxy = this.proxy("plugin-messaging");
		final BackendCore backend = this.backend("plugin-messaging", Role.AUTH, Duration.ofSeconds(60));

		final PlayerSession session = join(proxy, "Steve");

		backend.verifier().checkLogin(session.uuid());
		backend.verifier().joined(session.uuid());

		final var verifier = new pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier(new pl.kingdomcraft.authenticator.common.messaging.HmacSigner("another-secret-another-secret"), Duration.ofSeconds(30), CLOCK, LOGGER);
		final var payload = pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.payload(new pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage(session.uuid(),
				pl.kingdomcraft.authenticator.common.auth.AuthState.AUTHENTICATED, pl.kingdomcraft.authenticator.common.auth.AuthMethod.FORCED, 99));
		final byte[] forged = verifier.sign(pl.kingdomcraft.authenticator.common.messaging.Envelope.create(pl.kingdomcraft.authenticator.common.messaging.MessageTypes.AUTH_STATE, "velocity-1", payload, CLOCK))
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);

		assertFalse(this.backendCarrier.receive(pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport.Origin.TRUSTED_PEER, forged));
		Thread.sleep(300);

		assertFalse(this.gate.authenticated(session.uuid()));
		assertTrue(backend.status().stream().anyMatch(line -> line.contains("1 bad signature")), backend.status().toString());
	}

	@Test
	void theStatusSaysWhatTheServerIsDoing() throws Exception {
		this.proxy("plugin-messaging");

		final BackendCore backend = this.backend("plugin-messaging", Role.AUTH, Duration.ofSeconds(60));
		final List<String> status = backend.status();

		assertEquals("Node paper-auth-1, role AUTH", status.get(0));
		assertTrue(status.stream().anyMatch(line -> line.startsWith("Database: private endpoint")), status.toString());
		assertTrue(status.stream().anyMatch(line -> line.startsWith("Bridge: plugin-messaging")), status.toString());
		assertTrue(status.stream().anyMatch(line -> line.startsWith("Players known to the gate: 0")), status.toString());
	}

	@Test
	void snapshotsAreFilledInTheBackgroundAndNeverBlock() throws Exception {
		final ProxyCore proxy = this.proxy("database-only");
		final BackendCore backend = this.backend("database-only", Role.AUTH, Duration.ofSeconds(60));

		final PlayerSession session = join(proxy, "Steve");

		assertTrue(backend.snapshots().peek(session.uuid()).isEmpty(), "nothing yet: the placeholder shows 'loading'");

		backend.snapshots().refresh(session.uuid());

		assertTrue(waitFor(() -> backend.snapshots().peek(session.uuid()).isPresent(), 5_000));
		assertFalse(backend.snapshots().peek(session.uuid()).orElseThrow().registered());

		register(proxy, session);
		backend.snapshots().refresh(session.uuid());

		assertTrue(waitFor(() -> backend.snapshots().peek(session.uuid()).orElseThrow().registered(), 5_000));
		assertFalse(backend.snapshots().peek(session.uuid()).orElseThrow().premium());

		backend.snapshots().forget(session.uuid());

		assertTrue(backend.snapshots().peek(session.uuid()).isEmpty());
	}

	// ---- redis ----

	@Test
	void inRedisModeBothSidesTalkThroughRedis() throws Exception {
		assumeTrue(TestRedis.binaryAvailable(), "redis-server is not installed");

		try (TestRedis redis = TestRedis.start()) {
			final String prefix = "backend-" + UUID.randomUUID().toString().substring(0, 8) + ":";
			final Map<String, Object> redisSection = Map.of("use", "private", "prefix", prefix, "endpoints", Map.of("private", Map.of("host", "127.0.0.1", "port", redis.port(), "ssl", false, "connect-timeout-ms", 1000)));
			final Map<String, Object> backendRedis = Map.of("Use", "private", "Prefix", prefix, "Endpoints", Map.of("Private", Map.of("Host", "127.0.0.1", "Port", redis.port(), "Ssl", false, "Connect_Timeout_Ms", 1000)));

			final Map<String, Object> proxyRoot = new LinkedHashMap<>();

			proxyRoot.put("node-id", "velocity-1");
			proxyRoot.putAll(this.database("database"));
			proxyRoot.put("bridge", Map.of("mode", "redis", "secret", SECRET, "database-poll-seconds", 0));
			proxyRoot.put("redis", redisSection);
			proxyRoot.put("routing", Map.of("auth-servers", List.of("auth"), "lobby-servers", List.of("lobby")));
			proxyRoot.put("passwords", Map.of("bcrypt-cost", 4));
			proxyRoot.put("limits", Map.of("connections-per-ip", 1000));

			final ProxyCore proxy = ProxyCore.start(ProxySettings.parse(proxyRoot), CLOCK, LOGGER, null, List.of());

			this.toClose.add(proxy);
			proxy.activate(new StateWatcher.Handler() {
				@Override
				public void accountRemoved(PlayerSession session) {
					BackendCoreIntegrationTest.this.removed.add(session.username());
				}

				@Override
				public void typeChanged(PlayerSession session) {
				}

				@Override
				public void stateForced(PlayerSession session) {
				}
			}, session -> {
			});

			final Map<String, Object> backendRoot = new LinkedHashMap<>();

			backendRoot.put("Node_Id", "paper-auth-1");
			backendRoot.putAll(this.database("Database"));
			backendRoot.put("Bridge", Map.of("Mode", "redis", "Secret", SECRET));
			backendRoot.put("Redis", backendRedis);
			backendRoot.put("Passwords", Map.of("Bcrypt_Cost", 4));

			final BackendCore backend = BackendCore.start(BackendSettings.parse(backendRoot), Role.GAME, Duration.ofSeconds(60), this.gate, new JoinVerifier.Listener() {
				@Override
				public void authenticated(UUID player) {
				}

				@Override
				public void deauthenticated(UUID player) {
				}
			}, CLOCK, LOGGER, null, List.of());

			this.toClose.add(backend);
			backend.activate();

			assertTrue(waitFor(proxy.transport()::isConnected, 10_000));
			assertTrue(waitFor(backend.transport()::isConnected, 10_000));

			final PlayerSession session = join(proxy, "Steve");

			register(proxy, session);
			assertTrue(backend.verifier().checkLogin(session.uuid()).allowed());
			backend.verifier().joined(session.uuid());
			assertTrue(this.gate.authenticated(session.uuid()));

			final AdminService.Result result = backend.admin().unregister("Steve", "Admin");

			assertTrue(backend.notifyProxy(result.player(), result.change()));
			assertTrue(waitFor(() -> this.removed.contains("Steve"), 5_000), "the proxy heard the backend through Redis");
			assertTrue(waitFor(() -> !this.gate.authenticated(session.uuid()), 5_000), "and the backend heard the proxy");
		}
	}
}
