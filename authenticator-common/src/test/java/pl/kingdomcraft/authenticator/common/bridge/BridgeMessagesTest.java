package pl.kingdomcraft.authenticator.common.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChange;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AccountChangedMessage;
import pl.kingdomcraft.authenticator.common.bridge.BridgeMessages.AuthStateMessage;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;

class BridgeMessagesTest {

	private static Envelope envelope(String type, JsonObject payload) {
		return Envelope.create(type, "node", payload, Clock.systemUTC());
	}

	@Test
	void anAuthStateRoundTrips() {
		for (final AuthStateMessage message : new AuthStateMessage[] { new AuthStateMessage(UUID.randomUUID(), AuthState.AUTHENTICATED, AuthMethod.PREMIUM, 7),
				new AuthStateMessage(UUID.randomUUID(), AuthState.PENDING_LOGIN, null, 1), new AuthStateMessage(UUID.randomUUID(), AuthState.PENDING_REGISTER, null, 2) })
			assertEquals(message, BridgeMessages.authState(envelope(MessageTypes.AUTH_STATE, BridgeMessages.payload(message))).orElseThrow());
	}

	@Test
	void anAccountChangeRoundTrips() {
		for (final AccountChange change : AccountChange.values()) {
			final AccountChangedMessage message = new AccountChangedMessage(UUID.randomUUID(), change);

			assertEquals(message, BridgeMessages.accountChanged(envelope(MessageTypes.ACCOUNT_CHANGED, BridgeMessages.payload(message))).orElseThrow());
		}
	}

	@Test
	void malformedPayloadsAreNotMessagesAndNeverThrow() {
		final JsonObject empty = new JsonObject();
		final JsonObject badUuid = BridgeMessages.payload(new AuthStateMessage(UUID.randomUUID(), AuthState.PENDING_LOGIN, null, 1));
		badUuid.addProperty("uuid", "not-a-uuid");
		final JsonObject badState = BridgeMessages.payload(new AuthStateMessage(UUID.randomUUID(), AuthState.PENDING_LOGIN, null, 1));
		badState.addProperty("state", "ROOT");
		final JsonObject authenticatedWithoutMethod = BridgeMessages.payload(new AuthStateMessage(UUID.randomUUID(), AuthState.PENDING_LOGIN, null, 1));
		authenticatedWithoutMethod.addProperty("state", "AUTHENTICATED");

		for (final JsonObject payload : new JsonObject[] { empty, badUuid, badState, authenticatedWithoutMethod })
			assertTrue(BridgeMessages.authState(envelope(MessageTypes.AUTH_STATE, payload)).isEmpty(), payload.toString());

		assertTrue(BridgeMessages.accountChanged(envelope(MessageTypes.ACCOUNT_CHANGED, empty)).isEmpty());
	}

	@Test
	void aMessageOfAnotherTypeIsNotRead() {
		final AuthStateMessage message = new AuthStateMessage(UUID.randomUUID(), AuthState.PENDING_LOGIN, null, 1);

		assertTrue(BridgeMessages.authState(envelope(MessageTypes.ACCOUNT_CHANGED, BridgeMessages.payload(message))).isEmpty());
		assertTrue(BridgeMessages.accountChanged(envelope(MessageTypes.AUTH_STATE, BridgeMessages.payload(message))).isEmpty());
	}
}
