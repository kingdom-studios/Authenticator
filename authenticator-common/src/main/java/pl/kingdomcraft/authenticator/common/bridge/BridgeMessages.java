package pl.kingdomcraft.authenticator.common.bridge;

import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.MessageTypes;

/** Builds and reads the payloads of the bridge messages. Reading never throws: a malformed payload is simply not a message. */
public final class BridgeMessages {

	private BridgeMessages() {
	}

	/** What changed about an account behind the proxy's back. */
	public enum AccountChange {
		PASSWORD,
		TYPE,
		REMOVED,
		FORCED_LOGIN,
		FORCED_LOGOUT
	}

	/** A player's authentication state, from the proxy to the server the player is on. {@code epoch} counts up with every change of that player's state. */
	public record AuthStateMessage(UUID player, AuthState state, AuthMethod method, long epoch) {
	}

	/** An account was changed by an admin on a backend server, to the proxy. */
	public record AccountChangedMessage(UUID player, AccountChange change) {
	}

	public static JsonObject payload(AuthStateMessage message) {
		final JsonObject payload = new JsonObject();

		payload.addProperty("uuid", message.player().toString());
		payload.addProperty("state", message.state().name());

		if (message.method() != null)
			payload.addProperty("method", message.method().name());

		payload.addProperty("epoch", message.epoch());

		return payload;
	}

	public static JsonObject payload(AccountChangedMessage message) {
		final JsonObject payload = new JsonObject();

		payload.addProperty("uuid", message.player().toString());
		payload.addProperty("change", message.change().name());

		return payload;
	}

	public static Optional<AuthStateMessage> authState(Envelope envelope) {
		if (!MessageTypes.AUTH_STATE.equals(envelope.type()))
			return Optional.empty();

		try {
			final JsonObject payload = envelope.payload();
			final AuthState state = AuthState.valueOf(payload.get("state").getAsString());
			final AuthMethod method = payload.has("method") ? AuthMethod.valueOf(payload.get("method").getAsString()) : null;

			if (state == AuthState.AUTHENTICATED && method == null)
				return Optional.empty();

			return Optional.of(new AuthStateMessage(UUID.fromString(payload.get("uuid").getAsString()), state, method, payload.get("epoch").getAsLong()));

		} catch (final RuntimeException ex) {
			return Optional.empty();
		}
	}

	public static Optional<AccountChangedMessage> accountChanged(Envelope envelope) {
		if (!MessageTypes.ACCOUNT_CHANGED.equals(envelope.type()))
			return Optional.empty();

		try {
			final JsonObject payload = envelope.payload();

			return Optional.of(new AccountChangedMessage(UUID.fromString(payload.get("uuid").getAsString()), AccountChange.valueOf(payload.get("change").getAsString())));

		} catch (final RuntimeException ex) {
			return Optional.empty();
		}
	}
}
