package pl.kingdomcraft.authenticator.common.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChangedMessage;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;
import pl.kingdomcraft.authenticator.common.messaging.HmacSigner;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;
import pl.kingdomcraft.authenticator.common.messaging.RedisBus;
import pl.kingdomcraft.authenticator.common.messaging.RedisConfig;
import pl.kingdomcraft.authenticator.common.messaging.RedisEndpoint;
import pl.kingdomcraft.authenticator.common.support.TestRedis;

/** Each side of the Redis transport only hears what the other side may say. */
class RedisTransportIntegrationTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Logger LOGGER = Logger.getLogger("test");

	private static TestRedis redis;

	private final java.util.ArrayList<BridgeTransport> started = new java.util.ArrayList<>();

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
	void tearDown() {
		this.started.forEach(BridgeTransport::close);
	}

	private RedisTransport transport(String prefix, String node, RedisTransport.Role role) throws InterruptedException {
		final RedisConfig config = new RedisConfig(new ZonedEndpoints<>(Zone.PRIVATE, List.of(), Map.of(Zone.PRIVATE, new RedisEndpoint("127.0.0.1", redis.port(), "", "", false, true, "", "", 0, 1_000))), prefix);
		final EnvelopeVerifier verifier = new EnvelopeVerifier(new HmacSigner(SECRET), Duration.ofSeconds(30), Clock.systemUTC(), LOGGER);
		final RedisTransport transport = new RedisTransport(new RedisBus(config, verifier, node, Clock.systemUTC(), LOGGER), role, LOGGER);

		this.started.add(transport);
		transport.start();

		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

		while (!transport.isConnected() && System.nanoTime() < deadline)
			Thread.sleep(25);

		assertTrue(transport.isConnected(), node + " did not connect");

		return transport;
	}

	private static Envelope next(BlockingQueue<Envelope> queue) throws InterruptedException {
		final Envelope envelope = queue.poll(5, TimeUnit.SECONDS);

		assertNotNull(envelope, "no message arrived");

		return envelope;
	}

	@Test
	void anAuthStateGoesFromTheProxyToTheBackendOnly() throws Exception {
		final String prefix = "rt-" + UUID.randomUUID().toString().substring(0, 8) + ":";
		final RedisTransport proxy = this.transport(prefix, "velocity-1", RedisTransport.Role.PROXY);
		final RedisTransport backend = this.transport(prefix, "paper-1", RedisTransport.Role.BACKEND);

		final BlockingQueue<Envelope> atBackend = new LinkedBlockingQueue<>();
		final BlockingQueue<Envelope> atProxy = new LinkedBlockingQueue<>();

		backend.listen(atBackend::add);
		proxy.listen(atProxy::add);

		final AuthStateMessage message = new AuthStateMessage(UUID.randomUUID(), AuthState.AUTHENTICATED, AuthMethod.PREMIUM, 1);

		assertTrue(proxy.send(proxy.newEnvelope(MessageTypes.AUTH_STATE, BridgeMessages.payload(message)), Recipient.serverOf(message.player())));
		assertEquals(message, BridgeMessages.authState(next(atBackend)).orElseThrow());

		Thread.sleep(300);
		assertTrue(atProxy.isEmpty(), "the proxy must not hear its own state messages");
	}

	@Test
	void anAccountChangeGoesFromTheBackendToTheProxyOnly() throws Exception {
		final String prefix = "rt-" + UUID.randomUUID().toString().substring(0, 8) + ":";
		final RedisTransport proxy = this.transport(prefix, "velocity-1", RedisTransport.Role.PROXY);
		final RedisTransport backend = this.transport(prefix, "paper-1", RedisTransport.Role.BACKEND);

		final BlockingQueue<Envelope> atBackend = new LinkedBlockingQueue<>();
		final BlockingQueue<Envelope> atProxy = new LinkedBlockingQueue<>();

		backend.listen(atBackend::add);
		proxy.listen(atProxy::add);

		final AccountChangedMessage message = new AccountChangedMessage(UUID.randomUUID(), AccountChange.REMOVED);

		assertTrue(backend.send(backend.newEnvelope(MessageTypes.ACCOUNT_CHANGED, BridgeMessages.payload(message)), Recipient.proxyVia(message.player())));
		assertEquals(message, BridgeMessages.accountChanged(next(atProxy)).orElseThrow());

		Thread.sleep(300);
		assertTrue(atBackend.isEmpty(), "a backend must not hear admin messages");
	}

	@Test
	void sendReportsFailureWhenRedisIsUnreachable() throws Exception {
		final RedisConfig config = new RedisConfig(new ZonedEndpoints<>(Zone.PRIVATE, List.of(), Map.of(Zone.PRIVATE, new RedisEndpoint("127.0.0.1", 1, "", "", false, true, "", "", 0, 300))), "x:");
		final EnvelopeVerifier verifier = new EnvelopeVerifier(new HmacSigner(SECRET), Duration.ofSeconds(30), Clock.systemUTC(), LOGGER);
		final RedisTransport transport = new RedisTransport(new RedisBus(config, verifier, "velocity-1", Clock.systemUTC(), LOGGER), RedisTransport.Role.PROXY, LOGGER);

		this.started.add(transport);

		assertEquals(false, transport.send(transport.newEnvelope(MessageTypes.AUTH_STATE, new com.google.gson.JsonObject()), Recipient.serverOf(UUID.randomUUID())));
		assertEquals(BridgeMode.REDIS, transport.mode());
	}
}
