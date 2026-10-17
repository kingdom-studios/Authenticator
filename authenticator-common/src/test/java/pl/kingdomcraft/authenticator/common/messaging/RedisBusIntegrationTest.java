package pl.kingdomcraft.authenticator.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;
import pl.kingdomcraft.authenticator.common.support.TestRedis;
import redis.clients.jedis.Jedis;

/** The bridge against real {@code redis-server} processes, plain and with TLS. */
class RedisBusIntegrationTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Logger LOGGER = Logger.getLogger("test");
	private static final AtomicInteger NETWORKS = new AtomicInteger();

	private static TestRedis redis;

	private final List<RedisBus> buses = new ArrayList<>();
	private final List<TestRedis> servers = new ArrayList<>();

	@BeforeAll
	static void startRedis() throws Exception {
		redis = TestRedis.start();
	}

	@AfterAll
	static void stopRedis() throws Exception {
		if (redis != null)
			redis.close();
	}

	@AfterEach
	void tearDown() throws Exception {
		for (final RedisBus bus : this.buses)
			bus.close();

		for (final TestRedis server : this.servers)
			server.close();
	}

	// ---- helpers ----

	private static RedisEndpoint plain(int port) {
		return new RedisEndpoint("127.0.0.1", port, "", "", false, true, "", "", 0, 1_000);
	}

	private static RedisEndpoint tls(String host, int port, boolean verifyHostname, TestRedis server) {
		return new RedisEndpoint(host, port, "", "", true, verifyHostname, server == null ? "" : server.trustStore(), server == null ? "" : server.trustStorePassword(), 0, 1_000);
	}

	private static RedisEndpoint deadTls() {
		return new RedisEndpoint("127.0.0.1", 1, "", "", true, true, "", "", 0, 500);
	}

	private static String newPrefix() {
		return "t" + NETWORKS.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8) + ":";
	}

	private static RedisConfig config(String prefix, Zone use, List<Zone> fallback, RedisEndpoint publicEndpoint, RedisEndpoint privateEndpoint) {
		final Map<Zone, RedisEndpoint> endpoints = new java.util.EnumMap<>(Zone.class);

		if (publicEndpoint != null)
			endpoints.put(Zone.PUBLIC, publicEndpoint);

		if (privateEndpoint != null)
			endpoints.put(Zone.PRIVATE, privateEndpoint);

		return new RedisConfig(new ZonedEndpoints<>(use, fallback, endpoints), prefix, SECRET, 30);
	}

	private static RedisConfig plainConfig(String prefix) {
		return config(prefix, Zone.PRIVATE, List.of(), null, plain(redis.port()));
	}

	private RedisBus start(RedisConfig config, String node) {
		final RedisBus bus = new RedisBus(config, node, Clock.systemUTC(), LOGGER, 200);

		this.buses.add(bus);
		bus.start();

		return bus;
	}

	private RedisBus startConnected(RedisConfig config, String node) throws InterruptedException {
		final RedisBus bus = this.start(config, node);

		assertTrue(waitUntil(bus::isConnected, 10_000), node + " did not connect");

		return bus;
	}

	private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);

		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean())
				return true;

			Thread.sleep(25);
		}

		return condition.getAsBoolean();
	}

	private static BlockingQueue<Envelope> collect(RedisBus bus, String channel) {
		final BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
		bus.listen(channel, received::add);

		return received;
	}

	private static JsonObject payload(String key, String value) {
		final JsonObject payload = new JsonObject();
		payload.addProperty(key, value);

		return payload;
	}

	private static Envelope signed(String secret, long ts, JsonObject payload) {
		return new HmacSigner(secret).sign(new Envelope(Envelope.VERSION, UUID.randomUUID().toString(), MessageTypes.AUTH_STATE, ts, "attacker", payload, null));
	}

	private static void publishRaw(int port, String channel, String text) {
		try (Jedis jedis = new Jedis("127.0.0.1", port)) {
			jedis.publish(channel, text);
		}
	}

	private static Envelope next(BlockingQueue<Envelope> queue) throws InterruptedException {
		final Envelope envelope = queue.poll(5, TimeUnit.SECONDS);

		assertNotNull(envelope, "no message arrived");

		return envelope;
	}

	/**
	 * Publishes a marker and returns every message that was delivered before it. Messages are handled one at a time and in order, so
	 * once the marker arrives, anything published earlier that was going to be delivered already has been.
	 */
	private static List<Envelope> flush(RedisBus sender, BlockingQueue<Envelope> queue, String channel) throws InterruptedException {
		sender.publish(channel, sender.newEnvelope(MessageTypes.AUTH_STATE, payload("marker", "end")));

		final List<Envelope> delivered = new ArrayList<>();

		while (true) {
			final Envelope envelope = next(queue);

			if (envelope.payload().has("marker") && "end".equals(envelope.payload().get("marker").getAsString()))
				return delivered;

			delivered.add(envelope);
		}
	}

	// ---- delivery ----

	@Test
	void aMessageReachesAnotherNodeAndThePublisherItself() throws Exception {
		final String prefix = newPrefix();
		final RedisBus proxy = this.startConnected(plainConfig(prefix), "velocity-1");
		final RedisBus backend = this.startConnected(plainConfig(prefix), "paper-1");

		final BlockingQueue<Envelope> atBackend = collect(backend, backend.channels().state());
		final BlockingQueue<Envelope> atProxy = collect(proxy, proxy.channels().state());

		proxy.publish(proxy.channels().state(), proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("uuid", "u-1")));

		for (final BlockingQueue<Envelope> queue : List.of(atBackend, atProxy)) {
			final Envelope envelope = next(queue);

			assertEquals(MessageTypes.AUTH_STATE, envelope.type());
			assertEquals("velocity-1", envelope.node());
			assertEquals("u-1", envelope.payload().get("uuid").getAsString());
		}
	}

	@Test
	void channelsAreIndependent() throws Exception {
		final String prefix = newPrefix();
		final RedisBus proxy = this.startConnected(plainConfig(prefix), "velocity-1");
		final RedisBus backend = this.startConnected(plainConfig(prefix), "paper-1");

		final BlockingQueue<Envelope> state = collect(proxy, proxy.channels().state());
		final BlockingQueue<Envelope> admin = collect(proxy, proxy.channels().admin());

		backend.publish(backend.channels().admin(), backend.newEnvelope(MessageTypes.ACCOUNT_CHANGED, payload("uuid", "u-2")));

		assertEquals(MessageTypes.ACCOUNT_CHANGED, next(admin).type());
		assertTrue(state.isEmpty());
	}

	@Test
	void networksWithDifferentPrefixesDoNotSeeEachOther() throws Exception {
		final RedisBus one = this.startConnected(plainConfig(newPrefix()), "velocity-1");
		final RedisBus two = this.startConnected(plainConfig(newPrefix()), "velocity-2");

		final BlockingQueue<Envelope> atTwo = collect(two, two.channels().state());

		one.publish(one.channels().state(), one.newEnvelope(MessageTypes.AUTH_STATE, payload("a", "b")));
		Thread.sleep(500);

		assertTrue(atTwo.isEmpty());
	}

	// ---- hostile input ----

	@Test
	void forgedMessagesAreDroppedCountedAndNeverDelivered() throws Exception {
		final RedisBus proxy = this.startConnected(plainConfig(newPrefix()), "velocity-1");
		final BlockingQueue<Envelope> received = collect(proxy, proxy.channels().state());
		final String channel = proxy.channels().state();
		final long now = System.currentTimeMillis();

		final Envelope valid = signed(SECRET, now, payload("state", "PENDING_LOGIN"));
		final JsonObject changed = valid.payload().deepCopy();
		changed.addProperty("state", "AUTHENTICATED");

		publishRaw(redis.port(), channel, signed("another-secret-another-secret", now, payload("state", "AUTHENTICATED")).toJson()); // wrong secret
		publishRaw(redis.port(), channel, new Envelope(Envelope.VERSION, "x", MessageTypes.AUTH_STATE, now, "attacker", payload("state", "AUTHENTICATED"), null).toJson()); // unsigned
		publishRaw(redis.port(), channel, new Envelope(Envelope.VERSION, valid.id(), valid.type(), valid.ts(), valid.node(), changed, valid.sig()).toJson()); // tampered payload

		final List<Envelope> delivered = flush(proxy, received, channel);

		assertTrue(delivered.isEmpty(), "a forged message was delivered: " + delivered);
		assertEquals(3, proxy.rejectedSignatures());
	}

	@Test
	void staleAndFutureMessagesAreDropped() throws Exception {
		final RedisBus proxy = this.startConnected(plainConfig(newPrefix()), "velocity-1");
		final BlockingQueue<Envelope> received = collect(proxy, proxy.channels().state());
		final String channel = proxy.channels().state();
		final long now = System.currentTimeMillis();

		publishRaw(redis.port(), channel, signed(SECRET, now - 120_000, payload("a", "old")).toJson());
		publishRaw(redis.port(), channel, signed(SECRET, now + 120_000, payload("a", "future")).toJson());

		final List<Envelope> delivered = flush(proxy, received, channel);

		assertTrue(delivered.isEmpty(), "an old or future message was delivered: " + delivered);
		assertEquals(2, proxy.rejectedStale());
		assertEquals(0, proxy.rejectedSignatures());
	}

	@Test
	void aReplayedMessageIsDeliveredOnce() throws Exception {
		final RedisBus proxy = this.startConnected(plainConfig(newPrefix()), "velocity-1");
		final BlockingQueue<Envelope> received = collect(proxy, proxy.channels().state());
		final String channel = proxy.channels().state();

		final String json = signed(SECRET, System.currentTimeMillis(), payload("state", "AUTHENTICATED")).toJson();

		for (int i = 0; i < 3; i++)
			publishRaw(redis.port(), channel, json);

		final List<Envelope> delivered = flush(proxy, received, channel);

		assertEquals(1, delivered.size(), "the replayed copies were delivered too: " + delivered);
		assertEquals("AUTHENTICATED", delivered.get(0).payload().get("state").getAsString());
		assertEquals(2, proxy.rejectedStale());
	}

	@Test
	void malformedAndOversizedMessagesAreDropped() throws Exception {
		final RedisBus proxy = this.startConnected(plainConfig(newPrefix()), "velocity-1");
		final BlockingQueue<Envelope> received = collect(proxy, proxy.channels().state());
		final String channel = proxy.channels().state();

		publishRaw(redis.port(), channel, "garbage");
		publishRaw(redis.port(), channel, "{}");
		publishRaw(redis.port(), channel, "x".repeat(RedisBus.MAX_MESSAGE_LENGTH + 1));

		final List<Envelope> delivered = flush(proxy, received, channel);

		assertTrue(delivered.isEmpty(), "a malformed message was delivered: " + delivered);
		assertEquals(3, proxy.rejectedMalformed());
	}

	@Test
	void publishingAnOversizedMessageFailsBeforeItLeavesTheNode() throws Exception {
		final RedisBus proxy = this.startConnected(plainConfig(newPrefix()), "velocity-1");

		assertThrows(BusException.class, () -> proxy.publish(proxy.channels().state(), proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("big", "x".repeat(RedisBus.MAX_MESSAGE_LENGTH)))));
	}

	// ---- keys ----

	@Test
	void keysExpireOnTheirOwn() throws Exception {
		final RedisBus bus = this.startConnected(plainConfig(newPrefix()), "velocity-1");
		final String key = bus.channels().stateKey(UUID.randomUUID());

		bus.put(key, "AUTHENTICATED", 1);
		assertEquals("AUTHENTICATED", bus.get(key));

		assertTrue(waitUntil(() -> bus.get(key) == null, 4_000), "the key did not expire");

		bus.put(key, "x", 60);
		bus.delete(key);
		assertNull(bus.get(key));

		assertThrows(IllegalArgumentException.class, () -> bus.put(key, "x", 0));
	}

	@Test
	void aLockIsHeldByOneNodeAtATime() throws Exception {
		final RedisBus bus = this.startConnected(plainConfig(newPrefix()), "velocity-1");
		final String key = bus.channels().prefix() + "lock:cleanup";

		assertTrue(bus.tryLock(key, "a", 60_000));
		assertFalse(bus.tryLock(key, "b", 60_000));
	}

	@Test
	void publishingToAnUnreachableRedisFailsWithABusException() throws Exception {
		final RedisBus bus = this.start(config(newPrefix(), Zone.PRIVATE, List.of(), null, plain(1)), "velocity-1");

		assertFalse(waitUntil(bus::isConnected, 1_000));
		assertThrows(BusException.class, () -> bus.publish(bus.channels().state(), bus.newEnvelope(MessageTypes.AUTH_STATE, payload("a", "b"))));
		assertThrows(BusException.class, () -> bus.get("k"));
	}

	// ---- zones, TLS, failover ----

	@Test
	void usesThePublicEndpointOverTls() throws Exception {
		final TestRedis secure = TestRedis.startTls("localhost");
		this.servers.add(secure);

		final String prefix = newPrefix();
		final RedisBus proxy = this.startConnected(config(prefix, Zone.PUBLIC, List.of(), tls("localhost", secure.port(), true, secure), null), "velocity-1");

		assertEquals(Zone.PUBLIC, proxy.activeZone());

		final BlockingQueue<Envelope> received = collect(proxy, proxy.channels().state());
		proxy.publish(proxy.channels().state(), proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("a", "b")));

		assertEquals("b", next(received).payload().get("a").getAsString());
	}

	@Test
	void theProxyAndABackendServerEachStartOnTheirOwnZone() throws Exception {
		final TestRedis secure = TestRedis.startTls("localhost");
		this.servers.add(secure);

		// The same endpoints, as the shared part of two config files; only "use" differs, as in the real files.
		final RedisConfig proxy = config(newPrefix(), Zone.PUBLIC, List.of(Zone.PRIVATE), tls("localhost", secure.port(), true, secure), plain(redis.port()));
		final RedisConfig backend = config(proxy.prefix(), Zone.PRIVATE, List.of(Zone.PUBLIC), tls("localhost", secure.port(), true, secure), plain(redis.port()));

		assertEquals(Zone.PUBLIC, this.startConnected(proxy, "velocity-1").activeZone());
		assertEquals(Zone.PRIVATE, this.startConnected(backend, "paper-1").activeZone());
	}

	@Test
	void fallsBackToThePrivateEndpointWhenThePublicOneIsUnreachable() throws Exception {
		final String prefix = newPrefix();
		final RedisBus bus = this.startConnected(config(prefix, Zone.PUBLIC, List.of(Zone.PRIVATE), deadTls(), plain(redis.port())), "velocity-1");

		assertEquals(Zone.PRIVATE, bus.activeZone());

		final BlockingQueue<Envelope> received = collect(bus, bus.channels().state());
		bus.publish(bus.channels().state(), bus.newEnvelope(MessageTypes.AUTH_STATE, payload("a", "b")));

		assertEquals("b", next(received).payload().get("a").getAsString());
	}

	@Test
	void doesNotUseAnEndpointThatWasNotListedAsFallback() throws Exception {
		final RedisBus bus = this.start(config(newPrefix(), Zone.PUBLIC, List.of(), deadTls(), plain(redis.port())), "velocity-1");

		assertFalse(waitUntil(bus::isConnected, 2_500));
	}

	@Test
	void aCertificateForAnotherHostNameIsRejectedUnlessVerificationIsOff() throws Exception {
		final TestRedis secure = TestRedis.startTls("example.test");
		this.servers.add(secure);

		// Trusted certificate, but it is not valid for 127.0.0.1: only the host name check can refuse it.
		final RedisBus strict = this.startConnected(config(newPrefix(), Zone.PUBLIC, List.of(Zone.PRIVATE), tls("127.0.0.1", secure.port(), true, secure), plain(redis.port())), "velocity-1");
		assertEquals(Zone.PRIVATE, strict.activeZone(), "the certificate was accepted for the wrong host");

		final RedisBus lenient = this.startConnected(config(newPrefix(), Zone.PUBLIC, List.of(Zone.PRIVATE), tls("127.0.0.1", secure.port(), false, secure), plain(redis.port())), "velocity-2");
		assertEquals(Zone.PUBLIC, lenient.activeZone());
	}

	@Test
	void aSelfSignedCertificateIsRejectedWithoutATrustStore() throws Exception {
		final TestRedis secure = TestRedis.startTls("localhost");
		this.servers.add(secure);

		final RedisBus bus = this.startConnected(config(newPrefix(), Zone.PUBLIC, List.of(Zone.PRIVATE), tls("localhost", secure.port(), true, null), plain(redis.port())), "velocity-1");

		assertEquals(Zone.PRIVATE, bus.activeZone());
	}

	@Test
	void anUnreadableTrustStoreFailsAtStartupWithAClearMessage() {
		final RedisEndpoint broken = new RedisEndpoint("localhost", 6380, "", "", true, true, "/nonexistent/trust.p12", "x", 0, 1_000);

		final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new RedisBus(config(newPrefix(), Zone.PUBLIC, List.of(), broken, null), "velocity-1", Clock.systemUTC(), LOGGER));

		assertTrue(ex.getMessage().contains("/nonexistent/trust.p12"), ex.getMessage());
	}

	@Test
	void switchesBackToThePrimaryOnceItAnswersAndFallsBackAgainWhenItDies() throws Exception {
		final TestRedis primary = TestRedis.startTls("localhost");
		this.servers.add(primary);
		primary.stop();

		final RedisBus bus = this.startConnected(config(newPrefix(), Zone.PUBLIC, List.of(Zone.PRIVATE), tls("localhost", primary.port(), true, primary), plain(redis.port())), "velocity-1");
		assertEquals(Zone.PRIVATE, bus.activeZone());

		primary.restart();

		assertTrue(waitUntil(() -> bus.isConnected() && bus.activeZone() == Zone.PUBLIC, 20_000), "did not switch back to the primary, still on " + bus.activeZone());

		final BlockingQueue<Envelope> received = collect(bus, bus.channels().state());
		bus.publish(bus.channels().state(), bus.newEnvelope(MessageTypes.AUTH_STATE, payload("a", "on-primary")));
		assertEquals("on-primary", next(received).payload().get("a").getAsString());

		primary.stop();

		assertTrue(waitUntil(() -> bus.isConnected() && bus.activeZone() == Zone.PRIVATE, 20_000), "did not fall back again, now on " + bus.activeZone());

		bus.publish(bus.channels().state(), bus.newEnvelope(MessageTypes.AUTH_STATE, payload("a", "on-fallback")));
		assertEquals("on-fallback", next(received).payload().get("a").getAsString());
	}

	@Test
	void connectionListenersAreToldWhenTheLinkComesAndGoes() throws Exception {
		final TestRedis own = TestRedis.start();
		this.servers.add(own);

		final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
		final RedisBus bus = new RedisBus(config(newPrefix(), Zone.PRIVATE, List.of(), null, plain(own.port())), "velocity-1", Clock.systemUTC(), LOGGER, 200);

		this.buses.add(bus);
		bus.addConnectionListener(new RedisBus.ConnectionListener() {
			@Override
			public void onConnected() {
				events.add("up");
			}

			@Override
			public void onDisconnected() {
				events.add("down");
			}
		});
		bus.start();

		assertTrue(waitUntil(() -> events.contains("up"), 10_000));

		own.stop();
		assertTrue(waitUntil(() -> events.contains("down"), 10_000));

		own.restart();
		assertTrue(waitUntil(() -> events.stream().filter("up"::equals).count() == 2, 20_000), events.toString());
	}
}
