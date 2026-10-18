package pl.kingdomcraft.authenticator.common.bridge;

import java.time.Clock;
import java.util.function.Consumer;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.messaging.BusException;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.RedisBus;

/**
 * The Redis transport: the proxy publishes on the {@code state} channel and listens on {@code admin}; a backend server does the
 * opposite. Each side only subscribes to what the other side is allowed to send.
 */
public final class RedisTransport implements BridgeTransport {

	public enum Role {
		PROXY,
		BACKEND
	}

	private final RedisBus bus;
	private final Role role;
	private final Logger logger;

	public RedisTransport(RedisBus bus, Role role, Logger logger) {
		this.bus = bus;
		this.role = role;
		this.logger = logger;
	}

	public RedisBus bus() {
		return this.bus;
	}

	private String sendChannel() {
		return this.role == Role.PROXY ? this.bus.channels().state() : this.bus.channels().admin();
	}

	private String receiveChannel() {
		return this.role == Role.PROXY ? this.bus.channels().admin() : this.bus.channels().state();
	}

	@Override
	public BridgeMode mode() {
		return BridgeMode.REDIS;
	}

	@Override
	public String nodeId() {
		return this.bus.nodeId();
	}

	@Override
	public Clock clock() {
		return Clock.systemUTC();
	}

	@Override
	public void start() {
		this.bus.start();
	}

	@Override
	public void listen(Consumer<Envelope> listener) {
		this.bus.listen(this.receiveChannel(), listener);
	}

	@Override
	public boolean send(Envelope envelope, Recipient recipient) {
		try {
			this.bus.publish(this.sendChannel(), envelope);

			return true;

		} catch (final BusException ex) {
			this.logger.warning("Could not publish a bridge message: " + ex.getMessage());

			return false;
		}
	}

	@Override
	public boolean isConnected() {
		return this.bus.isConnected();
	}

	@Override
	public void close() {
		this.bus.close();
	}
}
