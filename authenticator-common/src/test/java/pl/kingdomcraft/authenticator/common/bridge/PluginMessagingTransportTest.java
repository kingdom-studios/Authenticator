package pl.kingdomcraft.authenticator.common.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.bridge.PluginMessagingTransport.Origin;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;
import pl.kingdomcraft.authenticator.common.messaging.HmacSigner;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;

class PluginMessagingTransportTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Logger LOGGER = Logger.getLogger("test");

	/** Two ends wired together in memory: whatever one delivers, the other receives as coming from a trusted peer. */
	private static final class Loopback extends PluginMessagingTransport {

		Loopback peer;
		final List<byte[]> sent = new ArrayList<>();
		boolean connected = true;

		Loopback(String nodeId, String secret) {
			super(new EnvelopeVerifier(new HmacSigner(secret), Duration.ofSeconds(30), Clock.systemUTC(), LOGGER), nodeId, Clock.systemUTC(), LOGGER);
		}

		@Override
		protected boolean deliver(Recipient recipient, byte[] data) {
			this.sent.add(data);

			if (!this.connected || this.peer == null)
				return false;

			return this.peer.receive(Origin.TRUSTED_PEER, data);
		}
	}

	private final Loopback proxy = new Loopback("velocity-1", SECRET);
	private final Loopback backend = new Loopback("paper-1", SECRET);
	private final BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();

	PluginMessagingTransportTest() {
		this.proxy.peer = this.backend;
		this.backend.peer = this.proxy;
		this.backend.listen(this.received::add);
	}

	@AfterEach
	void tearDown() {
		this.proxy.close();
		this.backend.close();
	}

	private static JsonObject payload(String value) {
		final JsonObject payload = new JsonObject();
		payload.addProperty("v", value);

		return payload;
	}

	private Envelope next() throws InterruptedException {
		final Envelope envelope = this.received.poll(5, TimeUnit.SECONDS);

		assertNotNull(envelope, "no message arrived");

		return envelope;
	}

	private byte[] signedBytes(String secret, long ts, JsonObject payload) {
		return new HmacSigner(secret).sign(new Envelope(Envelope.VERSION, UUID.randomUUID().toString(), MessageTypes.AUTH_STATE, ts, "attacker", payload, null)).toJson().getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void aMessageCrossesBetweenTheTwoEnds() throws Exception {
		final AuthStateMessage message = new AuthStateMessage(UUID.randomUUID(), AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 3);

		assertTrue(this.proxy.send(this.proxy.newEnvelope(MessageTypes.AUTH_STATE, BridgeMessages.payload(message)), Recipient.serverOf(message.player())));

		final Envelope envelope = this.next();

		assertEquals("velocity-1", envelope.node());
		assertEquals(message, BridgeMessages.authState(envelope).orElseThrow());
	}

	@Test
	void theSameStructureAsRedisIsOnTheWire() {
		this.proxy.send(this.proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("x")), Recipient.serverOf(UUID.randomUUID()));

		final String json = new String(this.proxy.sent.get(0), StandardCharsets.UTF_8);

		for (final String field : new String[] { "\"v\":1", "\"id\":", "\"type\":\"AUTH_STATE\"", "\"ts\":", "\"node\":\"velocity-1\"", "\"payload\":", "\"sig\":" })
			assertTrue(json.contains(field), "missing " + field + " in " + json);
	}

	/** The point of the HMAC and of the origin check being independent: a client that somehow knew the key still cannot use the channel. */
	@Test
	void aMessageFromAClientIsDroppedEvenWithAPerfectSignature() throws Exception {
		final byte[] perfect = this.signedBytes(SECRET, System.currentTimeMillis(), payload("forged"));

		assertFalse(this.backend.receive(Origin.CLIENT, perfect));
		assertEquals(1, this.backend.rejectedClientMessages());

		// The very same bytes from a trusted peer are fine, so only the origin made the difference.
		assertTrue(this.backend.receive(Origin.TRUSTED_PEER, perfect));
		assertEquals("forged", this.next().payload().get("v").getAsString());
	}

	@Test
	void aClientMessageIsNeverParsedOrRemembered() throws Exception {
		final byte[] perfect = this.signedBytes(SECRET, System.currentTimeMillis(), payload("x"));

		this.backend.receive(Origin.CLIENT, perfect);
		this.backend.receive(Origin.CLIENT, "garbage".getBytes(StandardCharsets.UTF_8));

		// Had the client copy been parsed, its id would now count as seen and the real message would be a "replay".
		assertTrue(this.backend.receive(Origin.TRUSTED_PEER, perfect));
		assertEquals(2, this.backend.rejectedClientMessages());
	}

	@Test
	void forgedStaleReplayedAndOversizedMessagesFromAPeerAreDropped() throws Exception {
		final long now = System.currentTimeMillis();
		final byte[] valid = this.signedBytes(SECRET, now, payload("ok"));

		assertFalse(this.backend.receive(Origin.TRUSTED_PEER, this.signedBytes("another-secret-another-secret", now, payload("wrong secret"))));
		assertFalse(this.backend.receive(Origin.TRUSTED_PEER, this.signedBytes(SECRET, now - 120_000, payload("old"))));
		assertFalse(this.backend.receive(Origin.TRUSTED_PEER, this.signedBytes(SECRET, now + 120_000, payload("future"))));
		assertFalse(this.backend.receive(Origin.TRUSTED_PEER, "garbage".getBytes(StandardCharsets.UTF_8)));
		assertFalse(this.backend.receive(Origin.TRUSTED_PEER, new byte[EnvelopeVerifier.MAX_MESSAGE_LENGTH * 4 + 1]));

		assertTrue(this.backend.receive(Origin.TRUSTED_PEER, valid));
		assertFalse(this.backend.receive(Origin.TRUSTED_PEER, valid), "a replay must be dropped");

		assertEquals("ok", this.next().payload().get("v").getAsString());
		assertTrue(this.received.isEmpty());
	}

	@Test
	void messagesAreDeliveredInOrderOnASingleThread() throws Exception {
		final List<String> threads = new ArrayList<>();
		this.backend.listen(envelope -> threads.add(Thread.currentThread().getName()));

		for (int i = 0; i < 50; i++)
			this.proxy.send(this.proxy.newEnvelope(MessageTypes.AUTH_STATE, payload(String.valueOf(i))), Recipient.serverOf(UUID.randomUUID()));

		for (int i = 0; i < 50; i++)
			assertEquals(String.valueOf(i), this.next().payload().get("v").getAsString());

		Thread.sleep(100);

		assertEquals(1, threads.stream().distinct().count());
		assertEquals("Authenticator-Bridge-Dispatch", threads.get(0));
	}

	@Test
	void aFailingListenerDoesNotStopTheOthers() throws Exception {
		this.backend.listen(envelope -> {
			throw new IllegalStateException("boom");
		});

		this.proxy.send(this.proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("a")), Recipient.serverOf(UUID.randomUUID()));
		this.proxy.send(this.proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("b")), Recipient.serverOf(UUID.randomUUID()));

		assertEquals("a", this.next().payload().get("v").getAsString());
		assertEquals("b", this.next().payload().get("v").getAsString());
	}

	@Test
	void sendReportsWhenThereWasNothingToCarryTheMessage() {
		this.proxy.connected = false;

		assertFalse(this.proxy.send(this.proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("x")), Recipient.serverOf(UUID.randomUUID())));
	}

	@Test
	void sendRefusesAMessageThatIsTooLarge() {
		assertFalse(this.proxy.send(this.proxy.newEnvelope(MessageTypes.AUTH_STATE, payload("x".repeat(EnvelopeVerifier.MAX_MESSAGE_LENGTH))), Recipient.serverOf(UUID.randomUUID())));
		assertTrue(this.proxy.sent.isEmpty());
	}

	@Test
	void aThrowingPlatformDoesNotEscapeFromSend() {
		final PluginMessagingTransport broken = new PluginMessagingTransport(new EnvelopeVerifier(new HmacSigner(SECRET), Duration.ofSeconds(30), Clock.systemUTC(), LOGGER), "x", Clock.systemUTC(), LOGGER) {
			@Override
			protected boolean deliver(Recipient recipient, byte[] data) {
				throw new IllegalStateException("player left");
			}
		};

		assertFalse(broken.send(broken.newEnvelope(MessageTypes.AUTH_STATE, payload("x")), Recipient.serverOf(UUID.randomUUID())));
		broken.close();
	}

	@Test
	void theChannelNameIsNamespaced() {
		assertEquals("authenticator:bridge", PluginMessagingTransport.CHANNEL);
		assertEquals(BridgeMode.PLUGIN_MESSAGING, this.proxy.mode());
	}
}
