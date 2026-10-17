package pl.kingdomcraft.authenticator.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class HmacSignerTest {

	private static final String SECRET = "0123456789abcdef-test-secret";

	/** Produced independently with Python's hmac/hashlib so the Java side and any other implementation cannot drift unnoticed. */
	private static final String AUTH_STATE_SIGNATURE = "pkRlettYbR21ubovBxuPOelf4b2c2IVRgNmqglkazFk=";
	private static final String LOGGED_OUT_SIGNATURE = "QmEJRk8HnO5aaglKVOiZp2G8OXMy9Ij1+/u5Poj1+wg=";

	private final HmacSigner signer = new HmacSigner(SECRET);

	private static Envelope authState() {
		final JsonObject payload = JsonParser.parseString("{\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"state\":\"AUTHENTICATED\",\"method\":\"PREMIUM\",\"epoch\":7}").getAsJsonObject();

		return new Envelope(1, "8d3c1c9e-0000-4000-8000-000000000001", "AUTH_STATE", 1760430000123L, "velocity-1", payload, null);
	}

	private static Envelope loggedOut() {
		final JsonObject payload = JsonParser.parseString("{\"zeta\":1,\"alpha\":{\"b\":true,\"a\":[1,\"x\\\"y\",null]},\"name\":\"Zoë \\n\"}").getAsJsonObject();

		return new Envelope(1, "8d3c1c9e-0000-4000-8000-000000000002", "LOGGED_OUT", 1760430000456L, "velocity-1", payload, null);
	}

	@Test
	void matchesSignaturesComputedByAnIndependentImplementation() {
		assertEquals(AUTH_STATE_SIGNATURE, signer.sign(authState()).sig());
		assertEquals(LOGGED_OUT_SIGNATURE, signer.sign(loggedOut()).sig());
	}

	@Test
	void signingStringStartsWithTheProtocolContext() {
		assertTrue(authState().signingString().startsWith("authenticator|1|8d3c1c9e-"));
	}

	@Test
	void verifiesItsOwnSignatures() {
		assertTrue(signer.verify(signer.sign(authState())));
	}

	@Test
	void rejectsUnsignedAndTamperedEnvelopes() {
		final Envelope signed = signer.sign(authState());

		assertFalse(signer.verify(authState()));
		assertFalse(signer.verify(signed.withSignature("AAAA")));

		final JsonObject changed = signed.payload().deepCopy();
		changed.addProperty("state", "PENDING_LOGIN");

		assertFalse(signer.verify(new Envelope(signed.v(), signed.id(), signed.type(), signed.ts(), signed.node(), changed, signed.sig())));
		assertFalse(signer.verify(new Envelope(signed.v(), signed.id(), "LOGGED_OUT", signed.ts(), signed.node(), signed.payload(), signed.sig())));
		assertFalse(signer.verify(new Envelope(signed.v(), signed.id(), signed.type(), signed.ts() + 1, signed.node(), signed.payload(), signed.sig())));
		assertFalse(signer.verify(new Envelope(signed.v(), signed.id(), signed.type(), signed.ts(), "evil", signed.payload(), signed.sig())));
	}

	@Test
	void anotherSecretDoesNotVerify() {
		assertFalse(new HmacSigner("another-secret-another-secret").verify(signer.sign(authState())));
	}

	@Test
	void signatureForAnotherProtocolWithTheSameSecretDoesNotVerify() throws Exception {
		// Same fields, but signed without the "authenticator|" context, the way a different protocol sharing the secret would.
		final Envelope envelope = authState();
		final String foreign = signer.sign(envelope.signingString().substring("authenticator|".length()));

		assertFalse(signer.verify(envelope.withSignature(foreign)));
	}

	@Test
	void shortSecretsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new HmacSigner("short"));
		assertThrows(IllegalArgumentException.class, () -> new HmacSigner(null));
		assertThrows(IllegalArgumentException.class, () -> new HmacSigner("x".repeat(HmacSigner.MIN_SECRET_LENGTH - 1)));
	}

	@Test
	void freshnessWorksInBothDirections() {
		final Envelope envelope = authState();
		final Duration skew = Duration.ofSeconds(30);
		final Instant sent = Instant.ofEpochMilli(envelope.ts());

		assertTrue(HmacSigner.isFresh(envelope, Clock.fixed(sent, ZoneOffset.UTC), skew));
		assertTrue(HmacSigner.isFresh(envelope, Clock.fixed(sent.plusSeconds(30), ZoneOffset.UTC), skew));
		assertTrue(HmacSigner.isFresh(envelope, Clock.fixed(sent.minusSeconds(30), ZoneOffset.UTC), skew));
		assertFalse(HmacSigner.isFresh(envelope, Clock.fixed(sent.plusSeconds(31), ZoneOffset.UTC), skew));
		assertFalse(HmacSigner.isFresh(envelope, Clock.fixed(sent.minusSeconds(31), ZoneOffset.UTC), skew));
	}

	@Test
	void differentIdsGiveDifferentSignatures() {
		final Envelope a = authState();
		final Envelope b = new Envelope(1, "other-id", a.type(), a.ts(), a.node(), a.payload(), null);

		assertNotEquals(signer.sign(a).sig(), signer.sign(b).sig());
	}
}
