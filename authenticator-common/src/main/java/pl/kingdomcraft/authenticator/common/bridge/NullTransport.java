package pl.kingdomcraft.authenticator.common.bridge;

import java.time.Clock;
import java.util.function.Consumer;

import pl.kingdomcraft.authenticator.common.messaging.Envelope;

/** The transport of {@link BridgeMode#DATABASE_ONLY}: sends nothing and receives nothing. */
public final class NullTransport implements BridgeTransport {

	private final String nodeId;
	private final Clock clock;

	public NullTransport(String nodeId, Clock clock) {
		this.nodeId = nodeId;
		this.clock = clock;
	}

	@Override
	public BridgeMode mode() {
		return BridgeMode.DATABASE_ONLY;
	}

	@Override
	public String nodeId() {
		return this.nodeId;
	}

	@Override
	public Clock clock() {
		return this.clock;
	}

	@Override
	public void start() {
	}

	@Override
	public void listen(Consumer<Envelope> listener) {
	}

	@Override
	public boolean send(Envelope envelope, Recipient recipient) {
		return false;
	}

	@Override
	public boolean isConnected() {
		return false;
	}

	@Override
	public void close() {
	}
}
