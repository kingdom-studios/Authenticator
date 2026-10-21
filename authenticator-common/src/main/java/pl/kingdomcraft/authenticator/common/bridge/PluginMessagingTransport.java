package pl.kingdomcraft.authenticator.common.bridge;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import pl.kingdomcraft.authenticator.common.messaging.BusException;
import pl.kingdomcraft.authenticator.common.messaging.Envelope;
import pl.kingdomcraft.authenticator.common.messaging.EnvelopeVerifier;

/**
 * The platform-independent half of the PluginMessaging transport: the same signed {@link Envelope} that Redis carries, as UTF-8 JSON
 * in a plugin message on {@value #CHANNEL}. A platform subclass only moves bytes (see {@link #deliver}) and reports each message it
 * receives with {@link #receive}.
 *
 * <p>Plugin messages are not secret and a player's client can send them too, so two independent defences apply:
 * <ol>
 * <li>a message that arrived over a <em>player's</em> connection is dropped before it is even parsed ({@link Origin#CLIENT});</li>
 * <li>everything else must carry a valid HMAC signature, be recent and be new, exactly as for Redis.</li>
 * </ol>
 */
public abstract class PluginMessagingTransport implements BridgeTransport {

	/** The plugin channel, namespaced as Minecraft requires. */
	public static final String CHANNEL = "authenticator:bridge";

	/** Where a plugin message came from. The platform decides, from the kind of connection the message arrived on. */
	public enum Origin {

		/** A backend server (on the proxy) or the proxy (on a backend server): the only trusted kind of sender. */
		TRUSTED_PEER,

		/** A player's client. Never allowed on this channel. */
		CLIENT
	}

	private final EnvelopeVerifier verifier;
	private final String nodeId;
	private final Clock clock;
	private final Logger logger;
	private final List<Consumer<Envelope>> listeners = new CopyOnWriteArrayList<>();
	private final AtomicLong rejectedClientMessages = new AtomicLong();

	private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "Authenticator-Bridge-Dispatch");
		thread.setDaemon(true);

		return thread;
	});

	protected PluginMessagingTransport(EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger) {
		this.verifier = verifier;
		this.nodeId = nodeId;
		this.clock = clock;
		this.logger = logger;
	}

	/**
	 * Moves bytes to the recipient over whatever connection the platform has.
	 *
	 * @return false if there was no connection to send them over
	 */
	protected abstract boolean deliver(Recipient recipient, byte[] data);

	/**
	 * Reports a plugin message received on {@value #CHANNEL}. Safe to call with anything, from any thread.
	 *
	 * @return true if the message was accepted and handed to the listeners
	 */
	public final boolean receive(Origin origin, byte[] data) {
		if (origin != Origin.TRUSTED_PEER) {
			this.rejectedClientMessages.incrementAndGet();
			this.verifier.warnLimited("Dropped a plugin message that a player's client sent on " + CHANNEL + ". Players cannot use this channel.");

			return false;
		}

		// UTF-8 needs at most 4 bytes per character, so anything longer cannot be a valid message.
		if (data.length > EnvelopeVerifier.MAX_MESSAGE_LENGTH * 4) {
			this.verifier.warnLimited("Dropped an oversized plugin message (" + data.length + " bytes) on " + CHANNEL);

			return false;
		}

		final Optional<Envelope> accepted = this.verifier.accept(new String(data, StandardCharsets.UTF_8), "plugin message on " + CHANNEL);

		if (accepted.isEmpty())
			return false;

		final Envelope envelope = accepted.get();

		try {
			this.dispatcher.execute(() -> {
				for (final Consumer<Envelope> listener : this.listeners)
					try {
						listener.accept(envelope);
					} catch (final RuntimeException ex) {
						this.logger.log(Level.SEVERE, "Bridge listener failed for " + envelope.type(), ex);
					}
			});

		} catch (final RuntimeException rejected) {
			return false; // shutting down
		}

		return true;
	}

	/** Messages dropped because a player's client sent them. */
	public long rejectedClientMessages() {
		return this.rejectedClientMessages.get();
	}

	@Override
	public BridgeMode mode() {
		return BridgeMode.PLUGIN_MESSAGING;
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
		this.listeners.add(listener);
	}

	@Override
	public boolean send(Envelope envelope, Recipient recipient) {
		final byte[] data;

		try {
			data = this.verifier.sign(envelope).getBytes(StandardCharsets.UTF_8);

		} catch (final BusException ex) {
			this.logger.warning(ex.getMessage());

			return false;
		}

		try {
			return this.deliver(recipient, data);

		} catch (final RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not send a bridge message to " + recipient, ex);

			return false;
		}
	}

	/** There is no connection of its own to lose: messages simply have nowhere to go while nobody is online. */
	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public void close() {
		this.dispatcher.shutdown();

		try {
			this.dispatcher.awaitTermination(2, TimeUnit.SECONDS);
		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
