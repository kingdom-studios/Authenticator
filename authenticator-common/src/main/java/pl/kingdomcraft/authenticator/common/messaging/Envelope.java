package pl.kingdomcraft.authenticator.common.messaging;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonParser;

/**
 * Wrapper around every Redis message.
 *
 * @param v       protocol version, currently {@value #VERSION}
 * @param id      random message id; receivers remember it for a while and drop a message they have already seen (replay protection)
 * @param type    message type, see {@link MessageTypes}
 * @param ts      creation time, epoch milliseconds; receivers drop messages that are too old or too far in the future
 * @param node    id of the publishing node
 * @param payload message body
 * @param sig     Base64 HMAC-SHA256 over {@link #signingString()}; null until signed
 */
public record Envelope(int v, String id, String type, long ts, String node, JsonObject payload, String sig) {

	public static final int VERSION = 1;

	/** Starts every signed text, so a signature made for another protocol that happens to share the secret never verifies here. */
	static final String SIGNING_CONTEXT = "authenticator";

	/** Nulls are written out, so what goes on the wire is exactly what {@link #signingString()} signed. */
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	public Envelope {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(payload, "payload");
	}

	/** Thrown when received text is not a valid envelope. */
	public static final class InvalidEnvelopeException extends Exception {
		private static final long serialVersionUID = 1L;

		public InvalidEnvelopeException(String message) {
			super(message);
		}

		public InvalidEnvelopeException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** New unsigned envelope with a random id, stamped with the clock's current time. */
	public static Envelope create(String type, String node, JsonObject payload, Clock clock) {
		return new Envelope(VERSION, UUID.randomUUID().toString(), type, clock.millis(), node, payload, null);
	}

	public Envelope withSignature(String signature) {
		return new Envelope(v, id, type, ts, node, payload, signature);
	}

	/** The exact text the signature covers: {@code authenticator|v|id|type|ts|node|canonical(payload)}. */
	public String signingString() {
		return SIGNING_CONTEXT + "|" + v + "|" + id + "|" + type + "|" + ts + "|" + node + "|" + CanonicalJson.write(payload);
	}

	public String toJson() {
		final JsonObject json = new JsonObject();

		json.addProperty("v", v);
		json.addProperty("id", id);
		json.addProperty("type", type);
		json.addProperty("ts", ts);
		json.addProperty("node", node);
		json.add("payload", payload);

		if (sig != null)
			json.addProperty("sig", sig);

		return GSON.toJson(json);
	}

	/**
	 * Parses and structurally validates a message. Does not check the signature.
	 *
	 * @throws InvalidEnvelopeException if the text is not JSON, a field is missing or has the wrong type, or the version is unsupported
	 */
	public static Envelope fromJson(String text) throws InvalidEnvelopeException {
		final JsonObject json;

		try {
			final JsonElement parsed = JsonParser.parseString(text);

			if (!parsed.isJsonObject())
				throw new InvalidEnvelopeException("Envelope must be a JSON object");

			json = parsed.getAsJsonObject();

		} catch (final JsonParseException | IllegalStateException ex) {
			throw new InvalidEnvelopeException("Envelope is not valid JSON", ex);
		}

		try {
			final int version = requireNumber(json, "v").getAsInt();

			if (version != VERSION)
				throw new InvalidEnvelopeException("Unsupported envelope version " + version);

			final JsonElement payload = json.get("payload");

			if (payload == null || !payload.isJsonObject())
				throw new InvalidEnvelopeException("Missing or non-object 'payload'");

			final JsonElement sig = json.get("sig");

			return new Envelope(version, requireString(json, "id"), requireString(json, "type"), requireNumber(json, "ts").getAsLong(), requireString(json, "node"),
					payload.getAsJsonObject(), sig == null || sig.isJsonNull() ? null : sig.getAsString());

		} catch (final ClassCastException | IllegalStateException | NumberFormatException | UnsupportedOperationException ex) {
			throw new InvalidEnvelopeException("Envelope has a field of the wrong type", ex);
		}
	}

	private static String requireString(JsonObject json, String key) throws InvalidEnvelopeException {
		final JsonElement element = json.get(key);

		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().isEmpty())
			throw new InvalidEnvelopeException("Missing or invalid '" + key + "'");

		return element.getAsString();
	}

	private static JsonPrimitive requireNumber(JsonObject json, String key) throws InvalidEnvelopeException {
		final JsonElement element = json.get(key);

		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
			throw new InvalidEnvelopeException("Missing or invalid '" + key + "'");

		return element.getAsJsonPrimitive();
	}
}
