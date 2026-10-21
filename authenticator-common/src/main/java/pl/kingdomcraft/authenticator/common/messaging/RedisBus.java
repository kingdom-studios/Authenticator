package pl.kingdomcraft.authenticator.common.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import com.google.gson.JsonObject;

import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints.Candidate;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.SetParams;

/**
 * Redis Pub/Sub transport for the bridge between the proxy and the backend servers. Pub/Sub delivers each message at most
 * once, so nothing sent here is authoritative: the database is. Redis only makes the change visible sooner.
 *
 * <p>A dedicated daemon thread keeps one subscription to every channel. It tries the configured endpoints in order
 * ({@code use} zone first, then each {@code fallback}), reconnects with exponential backoff, and while running on a fallback
 * keeps probing the primary endpoint to switch back as soon as it answers. Publishing uses a {@link JedisPool} on whichever
 * endpoint is subscribed.
 *
 * <p>A received message is delivered to listeners only if it is well formed, correctly signed, recent (within the configured
 * clock skew) and not seen before. Everything else is dropped and counted, never delivered. Listeners run one at a time, in
 * arrival order, on a separate dispatch thread so a slow listener never stalls the socket.
 */
public final class RedisBus implements AutoCloseable {

	/** Largest accepted or published message, in characters. */
	public static final int MAX_MESSAGE_LENGTH = EnvelopeVerifier.MAX_MESSAGE_LENGTH;

	private static final long INITIAL_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;
	private static final long PING_INTERVAL_MS = 15_000;
	private static final int SUBSCRIBER_SOCKET_TIMEOUT_MS = 45_000;
	private static final int PUBLISH_SOCKET_TIMEOUT_MS = 5_000;
	private static final int PROBE_TIMEOUT_MS = 2_000;

	/** While on a fallback endpoint, the primary is probed every this many ping intervals. */
	private static final int FAILBACK_EVERY_PINGS = 4;

	/** The endpoint messages are currently published on. */
	private record Active(int index, Zone zone, RedisEndpoint endpoint, JedisPool pool) {
	}

	/** Called from the subscriber thread; keep implementations fast. */
	public interface ConnectionListener {
		void onConnected();

		void onDisconnected();
	}

	private final List<Candidate<RedisEndpoint>> candidates;

	/** Socket factory per candidate (same index) for endpoints with their own trust store, null for the others. */
	private final List<SSLSocketFactory> socketFactories = new ArrayList<>();
	private final Channels channels;
	private final EnvelopeVerifier verifier;
	private final String nodeId;
	private final Clock clock;
	private final Logger logger;
	private final long pingIntervalMs;

	private final Map<String, List<Consumer<Envelope>>> listeners = new ConcurrentHashMap<>();
	private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

	private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(daemon("Authenticator-Redis-Dispatch"));
	private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(daemon("Authenticator-Redis-Ping"));

	private volatile BooleanSupplier traceEnabled = () -> false;
	private volatile Consumer<String> traceSink = line -> {
	};

	private volatile Active active;
	private volatile boolean running;
	private volatile boolean connected;
	private volatile Subscription subscription;
	private Thread subscriberThread;
	private int pingsOnFallback;

	public RedisBus(RedisConfig config, EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger) {
		this(config, verifier, nodeId, clock, logger, PING_INTERVAL_MS);
	}

	/** @param pingIntervalMs how often the subscription is kept alive and, on a fallback, the primary is probed; tests shorten it */
	RedisBus(RedisConfig config, EnvelopeVerifier verifier, String nodeId, Clock clock, Logger logger, long pingIntervalMs) {
		this.pingIntervalMs = pingIntervalMs;
		this.candidates = config.zones().candidates();

		for (final Candidate<RedisEndpoint> candidate : this.candidates)
			this.socketFactories.add(trustingSocketFactory(candidate.endpoint()));

		this.channels = new Channels(config.prefix());
		this.verifier = verifier;
		this.nodeId = nodeId;
		this.clock = clock;
		this.logger = logger;
		this.active = this.open(0);
	}

	public Channels channels() {
		return this.channels;
	}

	public String nodeId() {
		return this.nodeId;
	}

	/** Whether the subscriber is currently subscribed to every channel. */
	public boolean isConnected() {
		return this.connected;
	}

	/** The zone whose endpoint is in use; differs from the configured {@code use} zone while running on a fallback. */
	public Zone activeZone() {
		return this.active.zone();
	}

	public long rejectedSignatures() {
		return this.verifier.rejectedSignatures();
	}

	/** Messages dropped for being too old, too far in the future, or already seen. */
	public long rejectedStale() {
		return this.verifier.rejectedStale();
	}

	public long rejectedMalformed() {
		return this.verifier.rejectedMalformed();
	}

	/**
	 * Optional diagnostics: while {@code enabled} returns true every published and received message is described to
	 * {@code sink} (type, node, size, signature result; never the payload). Both are called from I/O threads, so they
	 * must be fast and thread-safe.
	 */
	public void setTrace(BooleanSupplier enabled, Consumer<String> sink) {
		this.traceEnabled = enabled;
		this.traceSink = sink;
	}

	private void trace(Supplier<String> line) {
		if (this.traceEnabled.getAsBoolean())
			this.traceSink.accept(line.get());
	}

	// ------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------

	/** Starts the subscriber thread. Returns immediately; connection problems are retried in the background. */
	public synchronized void start() {
		if (this.running)
			return;

		this.running = true;
		this.subscriberThread = new Thread(this::subscribeLoop, "Authenticator-Redis-Subscriber");
		this.subscriberThread.setDaemon(true);
		this.subscriberThread.start();

		this.pinger.scheduleWithFixedDelay(this::keepAlive, this.pingIntervalMs, this.pingIntervalMs, TimeUnit.MILLISECONDS);
	}

	@Override
	public synchronized void close() {
		this.running = false;

		final Subscription current = this.subscription;

		if (current != null)
			try {
				current.unsubscribe();
			} catch (final RuntimeException ignored) {
				// the connection is going away anyway
			}

		if (this.subscriberThread != null) {
			this.subscriberThread.interrupt();

			try {
				this.subscriberThread.join(2_000);
			} catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		this.pinger.shutdownNow();
		this.dispatcher.shutdown();

		try {
			this.dispatcher.awaitTermination(2, TimeUnit.SECONDS);
		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
		}

		this.active.pool().close();
	}

	// ------------------------------------------------------------------------------------------
	// Listening
	// ------------------------------------------------------------------------------------------

	/** Registers a listener for a channel. It only ever sees verified, fresh, first-time messages, including ones this node published itself. */
	public void listen(String channel, Consumer<Envelope> listener) {
		this.listeners.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(listener);
	}

	public void addConnectionListener(ConnectionListener listener) {
		this.connectionListeners.add(listener);
	}

	// ------------------------------------------------------------------------------------------
	// Publishing
	// ------------------------------------------------------------------------------------------

	/** Builds an unsigned envelope from this node. */
	public Envelope newEnvelope(String type, JsonObject payload) {
		return Envelope.create(type, this.nodeId, payload, this.clock);
	}

	/**
	 * Signs and publishes the envelope.
	 *
	 * @return number of subscribers that received it (0 is not an error: the database is the source of truth)
	 * @throws BusException if Redis is unreachable or the message is too large
	 */
	public long publish(String channel, Envelope envelope) {
		final String json = this.verifier.sign(envelope);

		try (Jedis jedis = this.active.pool().getResource()) {
			final long receivers = jedis.publish(channel, json);

			this.trace(() -> "published " + envelope.type() + " (" + json.length() + " chars) on " + channel + " to " + receivers + " subscriber(s)");

			return receivers;

		} catch (final RuntimeException ex) {
			this.trace(() -> "publish of " + envelope.type() + " on " + channel + " failed: " + ex.getMessage());

			throw new BusException("Could not publish to " + channel + ": " + ex.getMessage(), ex);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Keys
	// ------------------------------------------------------------------------------------------

	/**
	 * Stores a value that expires on its own.
	 *
	 * @throws BusException if Redis is unreachable
	 */
	public void put(String key, String value, long ttlSeconds) {
		if (ttlSeconds <= 0)
			throw new IllegalArgumentException("ttlSeconds must be positive");

		try (Jedis jedis = this.active.pool().getResource()) {
			jedis.set(key, value, SetParams.setParams().ex(ttlSeconds));

		} catch (final RuntimeException ex) {
			throw new BusException("Could not write " + key + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * @return the value, or null if the key is absent or expired
	 * @throws BusException if Redis is unreachable
	 */
	public String get(String key) {
		try (Jedis jedis = this.active.pool().getResource()) {
			return jedis.get(key);

		} catch (final RuntimeException ex) {
			throw new BusException("Could not read " + key + ": " + ex.getMessage(), ex);
		}
	}

	/** @throws BusException if Redis is unreachable */
	public void delete(String key) {
		try (Jedis jedis = this.active.pool().getResource()) {
			jedis.del(key);

		} catch (final RuntimeException ex) {
			throw new BusException("Could not delete " + key + ": " + ex.getMessage(), ex);
		}
	}

	/**
	 * Tries to take a short-lived lock with {@code SET key owner NX PX ttl}. Whoever gets {@code true} holds the lock until the TTL
	 * passes; there is deliberately no release, so the lock also rate-limits the work it guards across nodes. It only reduces
	 * duplicate work: correctness must never depend on it.
	 *
	 * @throws BusException if Redis is unreachable
	 */
	public boolean tryLock(String key, String owner, long ttlMillis) {
		if (ttlMillis <= 0)
			throw new IllegalArgumentException("ttlMillis must be positive");

		try (Jedis jedis = this.active.pool().getResource()) {
			return "OK".equals(jedis.set(key, owner, SetParams.setParams().nx().px(ttlMillis)));

		} catch (final RuntimeException ex) {
			throw new BusException("Could not take lock " + key + ": " + ex.getMessage(), ex);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Endpoints
	// ------------------------------------------------------------------------------------------

	private Active open(int index) {
		final Candidate<RedisEndpoint> candidate = this.candidates.get(index);
		final JedisPoolConfig poolConfig = new JedisPoolConfig();

		poolConfig.setMaxTotal(8);
		poolConfig.setMaxIdle(4);
		poolConfig.setMinIdle(0);
		poolConfig.setTestOnBorrow(true);

		return new Active(index, candidate.zone(), candidate.endpoint(), new JedisPool(poolConfig, address(candidate.endpoint()), this.client(index, PUBLISH_SOCKET_TIMEOUT_MS).build()));
	}

	/** Makes the endpoint that just got subscribed the one messages are published on. */
	private void activate(int index) {
		final Active previous = this.active;

		if (previous.index() == index)
			return;

		this.active = this.open(index);
		previous.pool().close();

		this.logger.info("Redis bridge is now using the " + this.active.zone().key() + " endpoint " + this.active.endpoint().address());
	}

	private static HostAndPort address(RedisEndpoint endpoint) {
		return new HostAndPort(endpoint.host(), endpoint.port());
	}

	private DefaultJedisClientConfig.Builder client(int index, int socketTimeoutMs) {
		final RedisEndpoint endpoint = this.candidates.get(index).endpoint();
		final DefaultJedisClientConfig.Builder client = DefaultJedisClientConfig.builder()
				.user(blankToNull(endpoint.user()))
				.password(blankToNull(endpoint.password()))
				.ssl(endpoint.ssl())
				.database(endpoint.database())
				.connectionTimeoutMillis(endpoint.connectTimeoutMs())
				.socketTimeoutMillis(socketTimeoutMs);

		if (this.socketFactories.get(index) != null)
			client.sslSocketFactory(this.socketFactories.get(index));

		// Without this Java accepts any certificate the trust store trusts, whatever host it was issued for.
		if (endpoint.ssl() && endpoint.verifyHostname()) {
			final SSLParameters parameters = new SSLParameters();
			parameters.setEndpointIdentificationAlgorithm("HTTPS");

			client.sslParameters(parameters);
		}

		return client;
	}

	private static SSLSocketFactory trustingSocketFactory(RedisEndpoint endpoint) {
		if (!endpoint.ssl() || endpoint.trustStore().isEmpty())
			return null;

		final Path path = Path.of(endpoint.trustStore());
		final String type = path.getFileName().toString().toLowerCase().endsWith(".jks") ? "JKS" : "PKCS12";

		try (InputStream stream = Files.newInputStream(path)) {
			final KeyStore keyStore = KeyStore.getInstance(type);
			keyStore.load(stream, endpoint.trustStorePassword().toCharArray());

			final TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trust.init(keyStore);

			final SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, trust.getTrustManagers(), null);

			return context.getSocketFactory();

		} catch (final IOException | GeneralSecurityException ex) {
			throw new IllegalStateException("Could not load the Redis trust store " + path + " (" + type + "): " + ex.getMessage(), ex);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Subscriber thread
	// ------------------------------------------------------------------------------------------

	private void subscribeLoop() {
		long backoff = INITIAL_BACKOFF_MS;

		while (this.running) {
			boolean wasConnected = false;

			// Every pass starts at the primary endpoint; a failed one falls through to the next without waiting.
			for (int index = 0; index < this.candidates.size() && this.running; index++) {
				final RedisEndpoint endpoint = this.candidates.get(index).endpoint();
				final Subscription current = new Subscription(index);

				try (Jedis jedis = new Jedis(address(endpoint), this.client(index, SUBSCRIBER_SOCKET_TIMEOUT_MS).build())) {
					this.subscription = current;

					jedis.subscribe(current, this.channels.all()); // blocks until unsubscribed or the connection drops

				} catch (final RuntimeException ex) {
					if (this.running)
						this.logger.log(Level.WARNING, "Redis subscription on the " + this.candidates.get(index).zone().key() + " endpoint " + endpoint.address() + " failed: " + ex.getMessage());

				} finally {
					this.subscription = null;
					this.setConnected(false);
				}

				if (current.everSubscribed) {
					wasConnected = true;
					break; // it worked and was then lost: back to the primary after a pause
				}
			}

			if (!this.running)
				break;

			if (wasConnected)
				backoff = INITIAL_BACKOFF_MS;

			final long sleep = backoff + ThreadLocalRandom.current().nextLong(backoff / 4 + 1);
			this.logger.info("Reconnecting to Redis in " + sleep + " ms");

			try {
				Thread.sleep(sleep);
			} catch (final InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}

			backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
		}
	}

	private void keepAlive() {
		final Subscription current = this.subscription;

		if (current == null || !current.isSubscribed()) {
			this.pingsOnFallback = 0;
			return;
		}

		try {
			current.ping();
		} catch (final RuntimeException ex) {
			// a dead socket makes the blocked subscribe() throw, which triggers the reconnect
		}

		if (current.index == 0) {
			this.pingsOnFallback = 0;
			return;
		}

		if (++this.pingsOnFallback % FAILBACK_EVERY_PINGS != 0)
			return;

		if (this.primaryAnswers()) {
			this.logger.info("The primary Redis endpoint answers again, switching back to it");

			try {
				current.unsubscribe(); // the subscribe loop then starts over at the primary
			} catch (final RuntimeException ex) {
				// the connection broke at the same moment; the loop restarts anyway
			}
		}
	}

	private boolean primaryAnswers() {
		final RedisEndpoint primary = this.candidates.get(0).endpoint();

		try (Jedis jedis = new Jedis(address(primary), this.client(0, PROBE_TIMEOUT_MS).build())) {
			return "PONG".equals(jedis.ping());

		} catch (final RuntimeException ex) {
			return false;
		}
	}

	private void setConnected(boolean value) {
		if (this.connected == value)
			return;

		this.connected = value;

		for (final ConnectionListener listener : this.connectionListeners)
			try {
				if (value)
					listener.onConnected();
				else
					listener.onDisconnected();
			} catch (final RuntimeException ex) {
				this.logger.log(Level.WARNING, "Redis connection listener failed", ex);
			}
	}

	private final class Subscription extends JedisPubSub {

		final int index;
		volatile boolean everSubscribed;

		Subscription(int index) {
			this.index = index;
		}

		@Override
		public void onSubscribe(String channel, int subscribedChannels) {
			if (subscribedChannels >= RedisBus.this.channels.all().length) {
				this.everSubscribed = true;

				RedisBus.this.activate(this.index);
				RedisBus.this.logger.info("Subscribed to Redis channels " + String.join(", ", RedisBus.this.channels.all()) + " on the " + RedisBus.this.active.zone().key() + " endpoint");
				RedisBus.this.setConnected(true);
			}
		}

		@Override
		public void onMessage(String channel, String message) {
			try {
				RedisBus.this.dispatcher.execute(() -> RedisBus.this.handle(channel, message));
			} catch (final RuntimeException rejected) {
				// shutting down
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Receiving
	// ------------------------------------------------------------------------------------------

	private void handle(String channel, String message) {
		final Optional<Envelope> accepted = this.verifier.accept(message, "Redis channel " + channel);

		if (accepted.isEmpty()) {
			this.trace(() -> "dropped a message on " + channel + " (" + message.length() + " chars)");
			return;
		}

		final Envelope envelope = accepted.get();

		this.trace(() -> "received " + envelope.type() + " from " + envelope.node() + " on " + channel + " (" + message.length() + " chars, signature ok)");

		final List<Consumer<Envelope>> targets = this.listeners.get(channel);

		if (targets == null)
			return;

		for (final Consumer<Envelope> listener : targets)
			try {
				listener.accept(envelope);
			} catch (final RuntimeException ex) {
				this.logger.log(Level.SEVERE, "Redis listener failed for " + envelope.type() + " on " + channel, ex);
			}
	}

	private static String blankToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	private static ThreadFactory daemon(String name) {
		return runnable -> {
			final Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);

			return thread;
		};
	}
}
