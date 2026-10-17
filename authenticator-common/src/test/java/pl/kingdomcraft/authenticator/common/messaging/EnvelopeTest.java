package pl.kingdomcraft.authenticator.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class EnvelopeTest {

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void roundTripsThroughJson() throws Exception {
		final Envelope original = new Envelope(1, "id-1", "AUTH_STATE", 1760430000123L, "velocity-1", json("{\"a\":1}"), "sig");
		final Envelope parsed = Envelope.fromJson(original.toJson());

		assertEquals(original, parsed);
	}

	@Test
	void signatureIsOptionalOnTheWire() throws Exception {
		final Envelope unsigned = Envelope.fromJson(new Envelope(1, "id-1", "AUTH_STATE", 1L, "n", json("{}"), null).toJson());

		assertNull(unsigned.sig());
		assertFalse(unsigned.toJson().contains("\"sig\""));
	}

	@Test
	void createStampsClockTimeAndRandomIds() {
		final Clock clock = Clock.fixed(Instant.ofEpochMilli(1760430000123L), ZoneOffset.UTC);
		final Envelope a = Envelope.create("LOGGED_OUT", "paper-1", json("{}"), clock);
		final Envelope b = Envelope.create("LOGGED_OUT", "paper-1", json("{}"), clock);

		assertEquals(1760430000123L, a.ts());
		assertEquals(Envelope.VERSION, a.v());
		assertNotEquals(a.id(), b.id());
	}

	@Test
	void nullsInThePayloadSurviveTheWireSoSignaturesStillVerify() throws Exception {
		final HmacSigner signer = new HmacSigner("0123456789abcdef-test-secret");
		final Envelope signed = signer.sign(new Envelope(1, "id-2", "ACCOUNT_UPDATED", 1L, "n", json("{\"reason\":null,\"x\":\"y\"}"), null));

		final Envelope received = Envelope.fromJson(signed.toJson());

		assertTrue(received.payload().has("reason"));
		assertTrue(signer.verify(received));
	}

	@Test
	void canonicalJsonSortsKeysRecursivelyWithoutWhitespace() {
		assertEquals("{\"a\":{\"x\":1,\"y\":2},\"b\":[3,1,2],\"c\":null}", CanonicalJson.write(json("{ \"c\": null, \"b\": [3, 1, 2], \"a\": {\"y\": 2, \"x\": 1} }")));
	}

	@Test
	void canonicalJsonEscapesOnlyWhatIsNecessary() {
		assertEquals("{\"k\":\"q\\\"b\\\\n\\n\\u0001é\"}", CanonicalJson.write(json("{\"k\":\"q\\\"b\\\\n\\n\\u0001é\"}")));
	}

	@Test
	void canonicalJsonPreservesNumberText() {
		// "big" sorts before "n"; numbers keep exactly the text they were received with.
		assertEquals("{\"big\":12345678901234567890,\"n\":1.50}", CanonicalJson.write(json("{\"n\":1.50,\"big\":12345678901234567890}")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "not json", "[]", "\"x\"", "{}", "{\"v\":1}", "{\"v\":2,\"id\":\"a\",\"type\":\"t\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"\",\"type\":\"t\",\"ts\":1,\"node\":\"n\",\"payload\":{}}", "{\"v\":1,\"id\":\"a\",\"type\":\"t\",\"ts\":\"1\",\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"a\",\"type\":\"t\",\"ts\":1,\"node\":\"n\",\"payload\":[]}", "{\"v\":1,\"id\":\"a\",\"type\":\"t\",\"ts\":1,\"node\":\"n\"}",
			"{\"v\":1,\"id\":1,\"type\":\"t\",\"ts\":1,\"node\":\"n\",\"payload\":{}}", "{\"v\":\"1\",\"id\":\"a\",\"type\":\"t\",\"ts\":1,\"node\":\"n\",\"payload\":{}}" })
	void rejectsInvalidEnvelopes(String text) {
		assertThrows(Envelope.InvalidEnvelopeException.class, () -> Envelope.fromJson(text));
	}

	@Test
	void anUnknownExtraFieldIsIgnoredAndNotSigned() throws Exception {
		final HmacSigner signer = new HmacSigner("0123456789abcdef-test-secret");
		final String wire = signer.sign(new Envelope(1, "id-3", "AUTH_STATE", 1L, "n", json("{}"), null)).toJson();
		final JsonObject withExtra = json(wire);
		withExtra.addProperty("extra", "ignored");

		assertTrue(signer.verify(Envelope.fromJson(withExtra.toString())));
	}
}
