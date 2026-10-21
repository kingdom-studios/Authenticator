package pl.kingdomcraft.authenticator.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class EnvelopeVerifierTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final List<String> warnings = new ArrayList<>();
	private final EnvelopeVerifier verifier = new EnvelopeVerifier(new HmacSigner(SECRET), Duration.ofSeconds(30), this.clock, this.logger());

	private Logger logger() {
		final Logger logger = Logger.getAnonymousLogger();

		logger.setUseParentHandlers(false);
		logger.addHandler(new Handler() {
			@Override
			public void publish(LogRecord record) {
				EnvelopeVerifierTest.this.warnings.add(record.getMessage());
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		});

		return logger;
	}

	private static JsonObject payload() {
		final JsonObject payload = new JsonObject();
		payload.addProperty("uuid", UUID.randomUUID().toString());

		return payload;
	}

	private Envelope envelope(long ts, JsonObject payload) {
		return new Envelope(Envelope.VERSION, UUID.randomUUID().toString(), MessageTypes.AUTH_STATE, ts, "velocity-1", payload, null);
	}

	private String wire(Envelope envelope) {
		return this.verifier.sign(envelope);
	}

	@Test
	void acceptsAValidMessageOnce() {
		final String wire = this.wire(this.envelope(NOW.toEpochMilli(), payload()));

		assertTrue(this.verifier.accept(wire, "test").isPresent());
		assertTrue(this.verifier.accept(wire, "test").isEmpty(), "the same message must not be accepted twice");
		assertEquals(1, this.verifier.rejectedStale());
	}

	@Test
	void dropsWrongSecretsUnsignedAndTamperedMessages() {
		final Envelope valid = new HmacSigner(SECRET).sign(this.envelope(NOW.toEpochMilli(), payload()));
		final JsonObject changed = valid.payload().deepCopy();
		changed.addProperty("state", "AUTHENTICATED");

		assertTrue(this.verifier.accept(new HmacSigner("another-secret-another-secret").sign(this.envelope(NOW.toEpochMilli(), payload())).toJson(), "test").isEmpty());
		assertTrue(this.verifier.accept(this.envelope(NOW.toEpochMilli(), payload()).toJson(), "test").isEmpty());
		assertTrue(this.verifier.accept(new Envelope(valid.v(), valid.id(), valid.type(), valid.ts(), valid.node(), changed, valid.sig()).toJson(), "test").isEmpty());

		assertEquals(3, this.verifier.rejectedSignatures());
	}

	@Test
	void dropsMessagesThatAreTooOldOrFromTheFuture() {
		assertTrue(this.verifier.accept(this.wire(this.envelope(NOW.minusSeconds(31).toEpochMilli(), payload())), "test").isEmpty());
		assertTrue(this.verifier.accept(this.wire(this.envelope(NOW.plusSeconds(31).toEpochMilli(), payload())), "test").isEmpty());
		assertTrue(this.verifier.accept(this.wire(this.envelope(NOW.minusSeconds(30).toEpochMilli(), payload())), "test").isPresent());

		assertEquals(2, this.verifier.rejectedStale());
	}

	@Test
	void dropsMalformedAndOversizedMessages() {
		assertTrue(this.verifier.accept("garbage", "test").isEmpty());
		assertTrue(this.verifier.accept("{}", "test").isEmpty());
		assertTrue(this.verifier.accept("x".repeat(EnvelopeVerifier.MAX_MESSAGE_LENGTH + 1), "test").isEmpty());

		assertEquals(3, this.verifier.rejectedMalformed());
	}

	@Test
	void aForgedIdDoesNotUseUpTheRealOne() {
		final Envelope real = this.envelope(NOW.toEpochMilli(), payload());
		final JsonObject forgedPayload = payload();

		// Same id, wrong secret: must be dropped without the id being remembered.
		assertTrue(this.verifier.accept(new HmacSigner("another-secret-another-secret").sign(new Envelope(real.v(), real.id(), real.type(), real.ts(), real.node(), forgedPayload, null)).toJson(), "test").isEmpty());
		assertTrue(this.verifier.accept(this.wire(real), "test").isPresent());
	}

	@Test
	void refusesToSignAMessageThatIsTooLarge() {
		final JsonObject big = new JsonObject();
		big.addProperty("data", "x".repeat(EnvelopeVerifier.MAX_MESSAGE_LENGTH));

		assertThrows(BusException.class, () -> this.verifier.sign(this.envelope(NOW.toEpochMilli(), big)));
	}

	@Test
	void warningsAreRateLimitedSoAFloodCannotFillTheLog() {
		for (int i = 0; i < 50; i++)
			this.verifier.accept("garbage", "test");

		assertEquals(1, this.warnings.size());
		assertFalse(this.warnings.get(0).contains("secret"), "a log line must not contain the secret");
	}

	@Test
	void acceptReturnsTheParsedEnvelope() {
		final Envelope sent = this.envelope(NOW.toEpochMilli(), payload());
		final Optional<Envelope> received = this.verifier.accept(this.wire(sent), "test");

		assertEquals(sent.id(), received.orElseThrow().id());
		assertEquals(sent.payload(), received.orElseThrow().payload());
	}
}
