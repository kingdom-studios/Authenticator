package pl.kingdomcraft.authenticator.common.support;

import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import pl.kingdomcraft.authenticator.common.auth.AuthService;
import pl.kingdomcraft.authenticator.common.auth.PasswordPolicy;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.auth.PremiumPolicy;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider;
import pl.kingdomcraft.authenticator.common.auth.SessionManager;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.hash.HashPool;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;
import pl.kingdomcraft.authenticator.common.premium.PremiumApi.Answer;
import pl.kingdomcraft.authenticator.common.premium.PremiumResolver;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.repository.IpBlockRepository;
import pl.kingdomcraft.authenticator.common.repository.PremiumCacheRepository;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository;
import pl.kingdomcraft.authenticator.common.repository.SessionRepository;
import pl.kingdomcraft.authenticator.common.settings.LimitSettings;
import pl.kingdomcraft.authenticator.common.settings.LoginSettings;
import pl.kingdomcraft.authenticator.common.settings.PremiumSettings;

/** The whole login machinery on a real database, with a cheap hash, a scripted Mojang and a clock the test moves. */
public final class AuthFixture implements AutoCloseable {

	public static final Logger LOGGER = Logger.getLogger("test");
	public static final String PASSWORD = "correct-horse";
	public static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

	public final TestMariaDb.Schema schema;
	public final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
	public final ScriptedPremiumApi api = new ScriptedPremiumApi();
	public final SessionManager sessions = new SessionManager();
	public final AccountRepository accounts;
	public final SessionRepository ipSessions;
	public final PresenceRepository presence;
	public final AuditRepository audit;
	public final IpBlockRepository blocks;
	public final PremiumCacheRepository premiumCache;
	public final PasswordHasher hasher = new PasswordHasher(new PasswordHasher.Settings(HashAlgo.BCRYPT, 4, 19_456, 2, 1));
	public final HashPool hashPool;
	public final PreLoginDecider decider;
	public final AuthService service;
	public final List<String> notifications = new CopyOnWriteArrayList<>();
	public final InetAddress ip;
	public final InetAddress otherIp;

	public AuthFixture(boolean sessionsEnabled, int hashThreads, int hashQueue) throws Exception {
		this.schema = TestMariaDb.createSchema(true);
		this.accounts = new AccountRepository(this.schema.database());
		this.ipSessions = new SessionRepository(this.schema.database());
		this.presence = new PresenceRepository(this.schema.database());
		this.audit = new AuditRepository(this.schema.database());
		this.blocks = new IpBlockRepository(this.schema.database());
		this.premiumCache = new PremiumCacheRepository(this.schema.database());
		this.hashPool = new HashPool(hashThreads, hashQueue);
		this.ip = InetAddress.getByName("203.0.113.7");
		this.otherIp = InetAddress.getByName("203.0.113.8");

		final LoginSettings login = new LoginSettings(Duration.ofSeconds(60), 3, sessionsEnabled ? Duration.ofMinutes(60) : Duration.ZERO, Pattern.compile("^[a-zA-Z0-9_]{3,16}$"));
		final LimitSettings limits = new LimitSettings(100, Duration.ofSeconds(10), 3, Duration.ofHours(168), 8, Duration.ofSeconds(300), Duration.ofMinutes(30));
		final PremiumResolver resolver = new PremiumResolver(List.of(this.api), null, PremiumResolver.Timing.defaults(), this.clock, LOGGER);

		this.decider = new PreLoginDecider(login, limits, new PremiumSettings(PremiumPolicy.PROTECT, List.of("mojang"), Duration.ofSeconds(3), PremiumResolver.Timing.defaults(), Duration.ofSeconds(60)),
				this.accounts, this.blocks, resolver, this.sessions, this.clock, LOGGER);
		this.service = new AuthService(this.schema.database(), this.accounts, this.ipSessions, this.presence, this.audit, this.blocks, this.hasher, this.hashPool,
				new PasswordPolicy(6, List.of("password123")), login, limits, this.sessions, this.decider, resolver, this.clock, "velocity-1", LOGGER);
		this.service.setStateListener(session -> this.notifications.add(session.username() + ":" + session.state() + ":" + session.method() + ":" + session.epoch()));

		this.api.answers.put("Notch", Answer.found(NOTCH));
	}

	public AuthFixture() throws Exception {
		this(true, 2, 16);
	}

	/** A new non-premium player who connected and has to register. */
	public PlayerSession newPlayer(String name) {
		return this.service.open(name, UUID.randomUUID(), this.ip, false).session();
	}

	/** A registered non-premium player who connected and has to log in. */
	public PlayerSession returningPlayer(String name) throws Exception {
		final PlayerSession first = this.newPlayer(name);

		this.service.register(first, PASSWORD, PASSWORD).get();
		this.service.close(first);
		this.ipSessions.delete(first.account().id());

		return this.service.open(name, first.uuid(), this.ip, false).session();
	}

	/** A registered non-premium player who connected from the given address, without going through registration (so no per-address limit applies). */
	public PlayerSession registeredPlayer(String name, InetAddress from) throws Exception {
		final UUID uuid = UUID.randomUUID();

		this.accounts.insert(new pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount(uuid, name, false, null, this.hasher.hash(PASSWORD).hash(), null, HashAlgo.BCRYPT, null));

		return this.service.open(name, uuid, from, false).session();
	}

	/** A registered non-premium player, logged in. */
	public PlayerSession loggedInPlayer(String name) throws Exception {
		final PlayerSession session = this.returningPlayer(name);

		this.service.login(session, PASSWORD).get();

		return session;
	}

	/** An original player who connected and passed Mojang's check. */
	public PlayerSession premiumPlayer(String name, UUID mojang) {
		final UUID uuid = this.service.profileUuid(name, true, mojang);

		return this.service.open(name, uuid, this.ip, true).session();
	}

	public int count(String sql) throws SQLException {
		return this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
				result.next();

				return result.getInt(1);
			}
		});
	}

	public void execute(String sql) throws SQLException {
		this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}

			return null;
		});
	}

	public static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
		final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);

		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean())
				return true;

			Thread.sleep(25);
		}

		return condition.getAsBoolean();
	}

	@Override
	public void close() throws Exception {
		this.hashPool.close();
		this.schema.close();
	}
}
