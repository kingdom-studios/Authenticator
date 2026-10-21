package pl.kingdomcraft.authenticator.velocity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChangedMessage;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.bridge.Recipient;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;
import pl.kingdomcraft.authenticator.common.messaging.HmacSigner;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;

/**
 * The carrier on the proxy, with the real Velocity event and channel classes. Players and servers are stand-ins (Velocity's own are not
 * available without a running proxy); what matters is that the carrier tells the two kinds of source apart by their type.
 */
class VelocityPluginMessagingTest {

	private static final Logger LOGGER = Logger.getLogger("test");
	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Clock CLOCK = Clock.systemUTC();

	private final UUID steve = UUID.randomUUID();
	private final List<byte[]> sentToServer = new CopyOnWriteArrayList<>();
	private final List<Envelope> received = new CopyOnWriteArrayList<>();
	private VelocityPluginMessaging carrier;

	@AfterEach
	void tearDown() {
		if (this.carrier != null)
			this.carrier.close();
	}

	private static EnvelopeVerifier verifier() {
		return new EnvelopeVerifier(new HmacSigner(SECRET), Duration.ofSeconds(30), CLOCK, LOGGER);
	}

	private static <T> T stand(Class<T> type, java.util.function.BiFunction<String, Object[], Object> answers) {
		return type.cast(Proxy.newProxyInstance(VelocityPluginMessagingTest.class.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			final Object answer = answers.apply(method.getName(), args);

			if (answer != null)
				return answer;

			return switch (method.getName()) {
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				case "toString" -> type.getSimpleName();
				default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
			};
		}));
	}

	private ServerConnection connection() {
		return stand(ServerConnection.class, (method, args) -> method.equals("sendPluginMessage") ? this.record((byte[]) args[1]) : null);
	}

	private Boolean record(byte[] data) {
		this.sentToServer.add(data);

		return Boolean.TRUE;
	}

	private Player player(UUID uuid, ServerConnection current) {
		return stand(Player.class, (method, args) -> switch (method) {
			case "getUniqueId" -> uuid;
			case "getCurrentServer" -> Optional.ofNullable(current);
			default -> null;
		});
	}

	private VelocityPluginMessaging carrier(Player online) {
		final ProxyServer proxy = stand(ProxyServer.class, (method, args) -> switch (method) {
			case "getPlayer" -> args[0] instanceof UUID uuid && online != null && online.getUniqueId().equals(uuid) ? Optional.of(online) : Optional.empty();
			case "getEventManager" -> stand(EventManager.class, (name, arguments) -> null); // closing unregisters, which does nothing here
			case "getChannelRegistrar" -> stand(ChannelRegistrar.class, (name, arguments) -> null);
			default -> null;
		});

		this.carrier = new VelocityPluginMessaging(proxy, new Object(), verifier(), "velocity-1", CLOCK, LOGGER);
		this.carrier.listen(this.received::add);

		return this.carrier;
	}

	private static byte[] signed(String type, JsonObject payload) {
		return verifier().sign(Envelope.create(type, "paper-1", payload, CLOCK)).getBytes(StandardCharsets.UTF_8);
	}

	private byte[] accountChanged() {
		return signed(MessageTypes.ACCOUNT_CHANGED, BridgeMessages.payload(new AccountChangedMessage(this.steve, AccountChange.REMOVED)));
	}

	private static PluginMessageEvent event(ChannelMessageSource source, ChannelIdentifier channel, byte[] data) {
		return new PluginMessageEvent(source, stand(ChannelMessageSink.class, (method, args) -> null), channel, data);
	}

	@Test
	void theChannelIsNamespacedTheWayMinecraftRequires() {
		assertEquals("authenticator:bridge", VelocityPluginMessaging.IDENTIFIER.getId());
		assertEquals(VelocityPluginMessaging.IDENTIFIER, MinecraftChannelIdentifier.create("authenticator", "bridge"));
	}

	@Test
	void aMessageFromABackendServerIsHandedOnAndNotForwarded() throws Exception {
		this.carrier(null);

		final CountDownLatch heard = new CountDownLatch(1);

		this.carrier.listen(envelope -> heard.countDown());

		final PluginMessageEvent event = event(this.connection(), VelocityPluginMessaging.IDENTIFIER, this.accountChanged());

		this.carrier.onPluginMessage(event);

		assertFalse(event.getResult().isAllowed(), "it is for this plugin only: not forwarded to the player or anyone else");
		assertTrue(heard.await(5, TimeUnit.SECONDS));
		assertEquals(MessageTypes.ACCOUNT_CHANGED, this.received.get(0).type());
		assertEquals(0, this.carrier.rejectedClientMessages());
	}

	@Test
	void aMessageFromAPlayersClientIsNeitherForwardedNorEverRead() throws Exception {
		this.carrier(null);

		final PluginMessageEvent event = event(this.player(this.steve, null), VelocityPluginMessaging.IDENTIFIER, this.accountChanged());

		this.carrier.onPluginMessage(event);
		Thread.sleep(200);

		assertFalse(event.getResult().isAllowed(), "it must not reach a backend server");
		assertEquals(List.of(), this.received, "even a correctly signed copy is not read when a client sent it");
		assertEquals(1, this.carrier.rejectedClientMessages());
	}

	@Test
	void aBackendServersMessageWithABadSignatureIsNotHandedOn() throws Exception {
		this.carrier(null);

		final byte[] forged = new EnvelopeVerifier(new HmacSigner("another-secret-another-secret"), Duration.ofSeconds(30), CLOCK, LOGGER)
				.sign(Envelope.create(MessageTypes.ACCOUNT_CHANGED, "paper-1", BridgeMessages.payload(new AccountChangedMessage(this.steve, AccountChange.REMOVED)), CLOCK)).getBytes(StandardCharsets.UTF_8);

		final PluginMessageEvent event = event(this.connection(), VelocityPluginMessaging.IDENTIFIER, forged);

		this.carrier.onPluginMessage(event);
		Thread.sleep(200);

		assertFalse(event.getResult().isAllowed(), "still consumed: nobody else should see it either");
		assertEquals(List.of(), this.received);
	}

	@Test
	void anotherPluginsChannelIsLeftAlone() throws Exception {
		this.carrier(null);

		final PluginMessageEvent event = event(this.connection(), MinecraftChannelIdentifier.create("someone", "else"), this.accountChanged());

		this.carrier.onPluginMessage(event);
		Thread.sleep(200);

		assertTrue(event.getResult().isAllowed(), "forwarded as usual");
		assertEquals(List.of(), this.received);
	}

	@Test
	void theProxySendsToTheServerThePlayerIsOn() {
		this.carrier(this.player(this.steve, this.connection()));

		final JsonObject payload = BridgeMessages.payload(new AuthStateMessage(this.steve, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 3));
		final Envelope envelope = Envelope.create(MessageTypes.AUTH_STATE, "velocity-1", payload, CLOCK);

		assertTrue(this.carrier.send(envelope, Recipient.serverOf(this.steve)));
		assertEquals(1, this.sentToServer.size());

		final Envelope read = verifier().accept(new String(this.sentToServer.get(0), StandardCharsets.UTF_8), "test").orElseThrow();

		assertEquals(new AuthStateMessage(this.steve, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, 3), BridgeMessages.authState(read).orElseThrow());
	}

	@Test
	void nothingIsSentWhenThereIsNoConnectionToCarryIt() {
		final Envelope envelope = Envelope.create(MessageTypes.AUTH_STATE, "velocity-1", new JsonObject(), CLOCK);

		this.carrier(null);

		assertFalse(this.carrier.send(envelope, Recipient.serverOf(this.steve)), "the player is not on the proxy");

		this.carrier(this.player(this.steve, null));

		assertFalse(this.carrier.send(envelope, Recipient.serverOf(this.steve)), "the player is between servers");
		assertEquals(List.of(), this.sentToServer);
	}

	@Test
	void theProxyNeverSendsToItself() {
		this.carrier(this.player(this.steve, this.connection()));

		assertFalse(this.carrier.send(Envelope.create(MessageTypes.ACCOUNT_CHANGED, "velocity-1", new JsonObject(), CLOCK), Recipient.proxyVia(this.steve)));
		assertEquals(List.of(), this.sentToServer);
	}
}
