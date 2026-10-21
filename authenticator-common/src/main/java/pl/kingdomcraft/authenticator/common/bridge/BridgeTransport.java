package pl.kingdomcraft.authenticator.common.bridge;

import java.time.Clock;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.messaging.Envelope;

/**
 * Carries signed {@link Envelope}s between the proxy and the backend servers. Delivery is best effort: a message may be lost (nobody
 * to carry it, a restart, Redis down), so nothing may depend on one arriving. Whatever matters is also in the database, and a
 * backend server checks it there whenever a player joins; a message only makes a change visible sooner.
 */
public interface BridgeTransport extends AutoCloseable {

	BridgeMode mode();

	String nodeId();

	Clock clock();

	void start();

	/** Registers a listener for verified messages from the other side. It runs on a thread of the transport, one message at a time. */
	void listen(Consumer<Envelope> listener);

	/**
	 * Hands a message to the transport; it is signed on the way out.
	 *
	 * @return false if there was nothing to carry it (no such player, no connection, Redis down); true only means it was sent, not that it arrived
	 */
	boolean send(Envelope envelope, Recipient recipient);

	/** Whether messages can currently be sent. Without a live connection to carry them, the database is all there is. */
	boolean isConnected();

	default Envelope newEnvelope(String type, JsonObject payload) {
		return Envelope.create(type, this.nodeId(), payload, this.clock());
	}

	@Override
	void close();
}
