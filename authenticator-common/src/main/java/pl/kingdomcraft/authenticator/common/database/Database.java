package pl.kingdomcraft.authenticator.common.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import pl.kingdomcraft.authenticator.common.config.Zone;
import pl.kingdomcraft.authenticator.common.config.ZonedEndpoints.Candidate;

/**
 * Owns the HikariCP pool and a bounded executor so that JDBC never runs on the Paper main thread or on Velocity's event loop.
 * Create it with {@link #connect}, which walks the configured endpoints (the {@code use} zone first, then each
 * {@code fallback}) and keeps the first one that answers.
 */
public final class Database implements AutoCloseable {

	/** A unit of JDBC work. */
	@FunctionalInterface
	public interface SqlFunction<T> {
		T apply(Connection connection) throws SQLException;
	}

	@FunctionalInterface
	public interface SqlCall<T> {
		T call() throws SQLException;
	}

	/**
	 * Watches synchronous JDBC calls for diagnostics, for example to flag a call made on a thread that must never block.
	 * Implementations run on whatever thread makes the call and must be cheap and thread-safe.
	 */
	@FunctionalInterface
	public interface CallObserver {
		/** Called just before {@link #query}/{@link #transaction} does any work; the returned task runs when it is over, success or not. */
		Runnable begin(String kind);
	}

	private static final Runnable NOTHING = () -> {
	};

	private final Zone zone;
	private final DatabaseEndpoint endpoint;
	private final HikariDataSource dataSource;
	private final ExecutorService executor;
	private volatile CallObserver observer = kind -> NOTHING;

	private Database(DatabaseConfig config, Zone zone, DatabaseEndpoint endpoint) {
		final HikariConfig hikari = new HikariConfig();

		hikari.setPoolName("Authenticator-Pool-" + zone.key());
		hikari.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
		hikari.setJdbcUrl(config.jdbcUrl(endpoint));
		hikari.setUsername(endpoint.user());
		hikari.setPassword(endpoint.password());
		hikari.setMaximumPoolSize(config.poolSize());
		hikari.setMinimumIdle(Math.min(2, config.poolSize()));
		hikari.setConnectionTimeout(config.connectionTimeoutMs());
		hikari.setInitializationFailTimeout(config.connectionTimeoutMs());
		hikari.setMaxLifetime(25 * 60_000L);

		// Idle connections over the public network are dropped by NAT and firewalls long before MariaDB's own timeout.
		hikari.setKeepaliveTime(zone == Zone.PUBLIC ? 30_000L : 5 * 60_000L);

		this.zone = zone;
		this.endpoint = endpoint;
		this.dataSource = new HikariDataSource(hikari);
		this.executor = Executors.newFixedThreadPool(config.poolSize(), new NamedThreadFactory());
	}

	/**
	 * Connects through the first endpoint that works.
	 *
	 * @throws SQLException if none of the configured endpoints can be reached; every failure is attached as a suppressed exception
	 */
	public static Database connect(DatabaseConfig config, Logger logger) throws SQLException {
		final List<String> failures = new ArrayList<>();
		final SQLException failure = new SQLException();

		for (final Candidate<DatabaseEndpoint> candidate : config.zones().candidates())
			try {
				final Database database = new Database(config, candidate.zone(), candidate.endpoint());

				logger.info("Connected to MariaDB through the " + candidate.zone().key() + " endpoint " + candidate.endpoint().address());

				return database;

			} catch (final RuntimeException ex) {
				failures.add(candidate.zone().key() + " endpoint " + candidate.endpoint().address() + ": " + rootMessage(ex));
				failure.addSuppressed(ex);

				logger.warning("Could not connect to MariaDB through the " + candidate.zone().key() + " endpoint " + candidate.endpoint().address() + ": " + rootMessage(ex));
			}

		throw new SQLException("Could not connect to MariaDB through any configured endpoint (" + String.join("; ", failures) + ")", failure);
	}

	private static String rootMessage(Throwable throwable) {
		Throwable root = throwable;

		while (root.getCause() != null && root.getCause() != root)
			root = root.getCause();

		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

	/** The zone whose endpoint is in use; differs from the configured {@code use} zone after a fallback. */
	public Zone zone() {
		return this.zone;
	}

	public DatabaseEndpoint endpoint() {
		return this.endpoint;
	}

	public Connection getConnection() throws SQLException {
		return this.dataSource.getConnection();
	}

	public void setObserver(CallObserver observer) {
		this.observer = observer == null ? kind -> NOTHING : observer;
	}

	/** Runs the function on the calling thread with a pooled connection (auto-commit on). */
	public <T> T query(SqlFunction<T> function) throws SQLException {
		final Runnable end = this.observer.begin("query");

		try (Connection connection = this.getConnection()) {
			return function.apply(connection);

		} finally {
			end.run();
		}
	}

	/** Runs the function in a transaction on the calling thread. Commits on success, rolls back on any failure. */
	public <T> T transaction(SqlFunction<T> function) throws SQLException {
		final Runnable end = this.observer.begin("transaction");

		try (Connection connection = this.getConnection()) {
			connection.setAutoCommit(false);
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

			try {
				final T result = function.apply(connection);
				connection.commit();

				return result;

			} catch (final SQLException | RuntimeException ex) {
				connection.rollback();

				throw ex;
			}

		} finally {
			end.run();
		}
	}

	/** Runs a transaction on the database executor. */
	public <T> CompletableFuture<T> transactionAsync(SqlFunction<T> function) {
		return this.supplyAsync(() -> this.transaction(function));
	}

	/** Runs a query on the database executor. */
	public <T> CompletableFuture<T> queryAsync(SqlFunction<T> function) {
		return this.supplyAsync(() -> this.query(function));
	}

	/**
	 * Runs blocking JDBC work on the database executor without holding a connection itself.
	 * Use this (not {@link #queryAsync}) for work that borrows its own connections, otherwise a small
	 * pool can deadlock waiting on itself.
	 */
	public <T> CompletableFuture<T> supplyAsync(SqlCall<T> call) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return call.call();
			} catch (final SQLException ex) {
				throw new CompletionException(ex);
			}
		}, this.executor);
	}

	public ExecutorService executor() {
		return this.executor;
	}

	public boolean isRunning() {
		return !this.dataSource.isClosed();
	}

	@Override
	public void close() {
		this.executor.shutdown();
		this.dataSource.close();
	}

	private static final class NamedThreadFactory implements ThreadFactory {
		private final AtomicInteger counter = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			final Thread thread = new Thread(runnable, "Authenticator-DB-" + this.counter.incrementAndGet());
			thread.setDaemon(true);

			return thread;
		}
	}
}
