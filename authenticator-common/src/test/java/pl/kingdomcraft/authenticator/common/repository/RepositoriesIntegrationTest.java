package pl.kingdomcraft.authenticator.common.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.auth.AuthMethod;
import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.DuplicateAccountException;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount;
import pl.kingdomcraft.authenticator.common.repository.PresenceRepository.Presence;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

class RepositoriesIntegrationTest {

	private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

	private TestMariaDb.Schema schema;
	private AccountRepository accounts;
	private SessionRepository sessions;
	private PresenceRepository presence;
	private IpBlockRepository blocks;
	private PremiumCacheRepository premiumCache;
	private AuditRepository audit;
	private InetAddress v4;
	private InetAddress v6;

	@BeforeEach
	void setUp() throws Exception {
		this.schema = TestMariaDb.createSchema(true);
		this.accounts = new AccountRepository(this.schema.database());
		this.sessions = new SessionRepository(this.schema.database());
		this.presence = new PresenceRepository(this.schema.database());
		this.blocks = new IpBlockRepository(this.schema.database());
		this.premiumCache = new PremiumCacheRepository(this.schema.database());
		this.audit = new AuditRepository(this.schema.database());
		this.v4 = InetAddress.getByName("203.0.113.7");
		this.v6 = InetAddress.getByName("2001:db8::7");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.schema != null)
			this.schema.close();
	}

	private Account cracked(String name) throws SQLException {
		return this.accounts.insert(new NewAccount(UUID.randomUUID(), name, false, null, "$2a$06$hash", null, HashAlgo.BCRYPT, this.v4));
	}

	// ---- accounts ----

	@Test
	void anAccountRoundTripsWithEveryField() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final Account saved = this.accounts.insert(new NewAccount(uuid, "Steve", false, null, "5e85320a", "Zk3pQ9", HashAlgo.SHA256, this.v6));

		assertTrue(saved.id() > 0);
		assertEquals(uuid, saved.uuid());
		assertEquals("Steve", saved.username());
		assertFalse(saved.premium());
		assertNull(saved.premiumUuid());
		assertEquals("5e85320a", saved.passwordHash());
		assertEquals("Zk3pQ9", saved.passwordSalt());
		assertEquals(HashAlgo.SHA256, saved.hashAlgo());
		assertEquals(this.v6, saved.registeredIp());
		assertNull(saved.lastLoginAt());
		assertEquals(0, saved.version());
		assertTrue(Duration.between(saved.registeredAt(), Instant.now()).abs().compareTo(Duration.ofSeconds(10)) < 0, "registered_at is not close to now: " + saved.registeredAt());
		assertTrue(saved.hasPassword());
	}

	@Test
	void anAccountIsFoundByNameIgnoringCaseByUuidAndByMojangUuid() throws Exception {
		final UUID mojang = UUID.randomUUID();
		final Account premium = this.accounts.insert(new NewAccount(mojang, "Notch", true, mojang, null, null, null, null));

		assertEquals(premium.id(), this.accounts.findByUsername("notch").orElseThrow().id());
		assertEquals(premium.id(), this.accounts.findByUsername("NOTCH").orElseThrow().id());
		assertEquals(premium.id(), this.accounts.findByUuid(mojang).orElseThrow().id());
		assertEquals(premium.id(), this.accounts.findByPremiumUuid(mojang).orElseThrow().id());
		assertTrue(this.accounts.findByUsername("Herobrine").isEmpty());
		assertTrue(this.accounts.findByUuid(UUID.randomUUID()).isEmpty());
		assertFalse(premium.hasPassword());
	}

	@Test
	void aTakenNameOrUuidIsReportedAsADuplicate() throws Exception {
		final Account first = this.cracked("Steve");

		assertThrows(DuplicateAccountException.class, () -> this.cracked("STEVE"));
		assertThrows(DuplicateAccountException.class, () -> this.accounts.insert(new NewAccount(first.uuid(), "Other", false, null, "h", null, HashAlgo.BCRYPT, null)));
		assertEquals(1, this.accounts.count());
	}

	@Test
	void changingThePasswordNeedsTheVersionTheCallerRead() throws Exception {
		final Account account = this.accounts.insert(new NewAccount(UUID.randomUUID(), "Steve", false, null, "old", "salt", HashAlgo.SHA256, null));

		assertTrue(this.accounts.updatePassword(account.id(), account.version(), "new", HashAlgo.BCRYPT));
		assertFalse(this.accounts.updatePassword(account.id(), account.version(), "stale", HashAlgo.BCRYPT), "a second writer with the old version must lose");

		final Account updated = this.accounts.findByUsername("Steve").orElseThrow();

		assertEquals("new", updated.passwordHash());
		assertEquals(HashAlgo.BCRYPT, updated.hashAlgo());
		assertNull(updated.passwordSalt(), "a new hash never keeps the old salt");
		assertEquals(account.version() + 1, updated.version());
	}

	@Test
	void anAccountBecomesPremiumKeepingItsUuidAndCrackedAgainWithAPassword() throws Exception {
		final Account account = this.cracked("Steve");
		final UUID mojang = UUID.randomUUID();

		assertTrue(this.accounts.setPremium(account.id(), account.version(), mojang));

		final Account premium = this.accounts.findByUsername("Steve").orElseThrow();

		assertTrue(premium.premium());
		assertEquals(mojang, premium.premiumUuid());
		assertEquals(account.uuid(), premium.uuid(), "the in-game UUID must not change");
		assertEquals(account.version() + 1, premium.version());

		assertTrue(this.accounts.setCracked(premium.id(), premium.version(), "$2a$06$fresh", HashAlgo.BCRYPT));

		final Account back = this.accounts.findByUsername("Steve").orElseThrow();

		assertFalse(back.premium());
		assertNull(back.premiumUuid());
		assertEquals("$2a$06$fresh", back.passwordHash());
		assertEquals(account.uuid(), back.uuid());
	}

	@Test
	void aStaleVersionStopsTheAccountTypeChange() throws Exception {
		final Account account = this.cracked("Steve");

		assertTrue(this.accounts.updatePassword(account.id(), account.version(), "x", HashAlgo.BCRYPT));
		assertFalse(this.accounts.setPremium(account.id(), account.version(), UUID.randomUUID()));
		assertFalse(this.accounts.setCracked(account.id(), account.version(), "h", HashAlgo.BCRYPT));
		assertFalse(this.accounts.rename(account.id(), account.version(), "Other"));
	}

	@Test
	void anAccountCanBeRenamedUnlessTheNameIsTaken() throws Exception {
		final Account steve = this.cracked("Steve");
		this.cracked("Alex");

		assertThrows(DuplicateAccountException.class, () -> this.accounts.rename(steve.id(), steve.version(), "alex"));
		assertTrue(this.accounts.rename(steve.id(), steve.version(), "Stevie"));
		assertEquals("Stevie", this.accounts.findByUuid(steve.uuid()).orElseThrow().username());
		assertTrue(this.accounts.findByUsername("Steve").isEmpty());
	}

	@Test
	void recordingALoginDoesNotChangeTheVersion() throws Exception {
		final Account account = this.cracked("Steve");

		this.accounts.recordLogin(account.id(), this.v6, NOW);

		final Account after = this.accounts.findByUsername("Steve").orElseThrow();

		assertEquals(NOW, after.lastLoginAt());
		assertEquals(this.v6, after.lastLoginIp());
		assertEquals(account.version(), after.version());
	}

	@Test
	void accountsCanBeDeletedAndCountedPerAddress() throws Exception {
		final Account a = this.cracked("A");
		this.cracked("B");
		this.accounts.insert(new NewAccount(UUID.randomUUID(), "C", false, null, "h", null, HashAlgo.BCRYPT, this.v6));

		assertEquals(2, this.accounts.countRegisteredFrom(this.v4, NOW.minus(Duration.ofDays(1))));
		assertEquals(1, this.accounts.countRegisteredFrom(this.v6, NOW.minus(Duration.ofDays(1))));
		assertEquals(0, this.accounts.countRegisteredFrom(this.v4, NOW.plus(Duration.ofDays(1))), "only registrations since the given time count");

		assertTrue(this.accounts.delete(a.id()));
		assertFalse(this.accounts.delete(a.id()));
		assertEquals(1, this.accounts.countRegisteredFrom(this.v4, NOW.minus(Duration.ofDays(1))));
	}

	@Test
	void versionsReportExistingAccountsOnly() throws Exception {
		final Account a = this.cracked("A");
		final Account b = this.cracked("B");
		this.accounts.updatePassword(b.id(), b.version(), "x", HashAlgo.BCRYPT);

		final Map<UUID, Long> versions = this.accounts.versions(Set.of(a.uuid(), b.uuid(), UUID.randomUUID()));

		assertEquals(Map.of(a.uuid(), 0L, b.uuid(), 1L), versions);
		assertTrue(this.accounts.versions(Set.of()).isEmpty());
	}

	// ---- IP sessions ----

	@Test
	void aSessionIsValidOnlyForItsAddressAndUntilItExpires() throws Exception {
		final Account account = this.cracked("Steve");

		this.sessions.save(account.id(), this.v4, NOW.plus(Duration.ofHours(1)));

		assertTrue(this.sessions.findValid(account.id(), this.v4, NOW).isPresent());
		assertTrue(this.sessions.findValid(account.id(), this.v6, NOW).isEmpty(), "another address");
		assertTrue(this.sessions.findValid(account.id(), this.v4, NOW.plus(Duration.ofHours(2))).isEmpty(), "expired");
		assertTrue(this.sessions.findValid(account.id() + 1, this.v4, NOW).isEmpty(), "another account");
	}

	@Test
	void aNewSessionReplacesTheOldOne() throws Exception {
		final Account account = this.cracked("Steve");

		this.sessions.save(account.id(), this.v4, NOW.plus(Duration.ofHours(1)));
		this.sessions.save(account.id(), this.v6, NOW.plus(Duration.ofHours(1)));

		assertTrue(this.sessions.findValid(account.id(), this.v4, NOW).isEmpty());
		assertEquals(this.v6, this.sessions.findValid(account.id(), this.v6, NOW).orElseThrow().ip());
	}

	@Test
	void sessionsCanBeDeletedAndExpiredOnesSwept() throws Exception {
		final Account a = this.cracked("A");
		final Account b = this.cracked("B");

		this.sessions.save(a.id(), this.v4, NOW.minus(Duration.ofMinutes(1)));
		this.sessions.save(b.id(), this.v4, NOW.plus(Duration.ofHours(1)));

		assertEquals(1, this.sessions.deleteExpired(NOW));
		assertTrue(this.sessions.findValid(b.id(), this.v4, NOW).isPresent());

		this.sessions.delete(b.id());
		assertTrue(this.sessions.findValid(b.id(), this.v4, NOW).isEmpty());
	}

	// ---- presence ----

	@Test
	void everyChangeOfAPlayersStateCountsTheEpochUp() throws Exception {
		final Account account = this.cracked("Steve");
		final UUID uuid = account.uuid();

		assertEquals(1, this.presence.save(uuid, account.id(), AuthState.PENDING_LOGIN, null, "velocity-1"));
		assertEquals(2, this.presence.save(uuid, account.id(), AuthState.AUTHENTICATED, AuthMethod.PASSWORD, "velocity-1"));
		assertEquals(3, this.presence.save(uuid, account.id(), AuthState.AUTHENTICATED, AuthMethod.FORCED, "velocity-1"));

		final Presence found = this.presence.find(uuid).orElseThrow();

		assertEquals(AuthState.AUTHENTICATED, found.state());
		assertEquals(AuthMethod.FORCED, found.method());
		assertEquals(3, found.epoch());
		assertEquals(account.id(), found.accountId());
		assertEquals("velocity-1", found.proxyId());
	}

	@Test
	void aNewPlayerWhoHasNotRegisteredHasNoAccountYet() throws Exception {
		final UUID uuid = UUID.randomUUID();

		this.presence.save(uuid, null, AuthState.PENDING_REGISTER, null, "velocity-1");

		final Presence found = this.presence.find(uuid).orElseThrow();

		assertNull(found.accountId());
		assertNull(found.method());
		assertEquals(AuthState.PENDING_REGISTER, found.state());
	}

	@Test
	void anAuthenticatedPlayerWithoutAMethodIsRefusedByTheDatabase() throws Exception {
		final Account account = this.cracked("Steve");

		assertThrows(SQLException.class, () -> this.presence.save(account.uuid(), account.id(), AuthState.AUTHENTICATED, null, "velocity-1"));
		assertThrows(SQLException.class, () -> this.presence.save(UUID.randomUUID(), null, AuthState.AUTHENTICATED, AuthMethod.PASSWORD, "velocity-1"));
	}

	@Test
	void presenceCanBeReadInBulkAndDeleted() throws Exception {
		final Account a = this.cracked("A");
		final Account b = this.cracked("B");

		this.presence.save(a.uuid(), a.id(), AuthState.PENDING_LOGIN, null, "velocity-1");
		this.presence.save(b.uuid(), b.id(), AuthState.PENDING_LOGIN, null, "velocity-2");

		assertEquals(Set.of(a.uuid(), b.uuid()), this.presence.findAll(Set.of(a.uuid(), b.uuid(), UUID.randomUUID())).keySet());
		assertTrue(this.presence.findAll(Set.of()).isEmpty());

		this.presence.delete(a.uuid());

		assertTrue(this.presence.find(a.uuid()).isEmpty());
		assertTrue(this.presence.find(b.uuid()).isPresent());
	}

	@Test
	void aProxyCanClearTheRowsItLeftBehind() throws Exception {
		final Account a = this.cracked("A");
		final Account b = this.cracked("B");

		this.presence.save(a.uuid(), a.id(), AuthState.PENDING_LOGIN, null, "velocity-1");
		this.presence.save(b.uuid(), b.id(), AuthState.PENDING_LOGIN, null, "velocity-2");

		assertEquals(1, this.presence.deleteOwnedBy("velocity-1"));
		assertTrue(this.presence.find(a.uuid()).isEmpty());
		assertTrue(this.presence.find(b.uuid()).isPresent(), "another proxy's rows must stay");
	}

	// ---- IP blocks ----

	@Test
	void anAddressStaysBlockedUntilTheBlockEndsAndABlockIsNeverShortened() throws Exception {
		this.blocks.block(this.v4, NOW.plus(Duration.ofHours(1)), "too many wrong passwords");

		assertEquals(NOW.plus(Duration.ofHours(1)), this.blocks.blockedUntil(this.v4, NOW).orElseThrow());
		assertTrue(this.blocks.blockedUntil(this.v6, NOW).isEmpty());
		assertTrue(this.blocks.blockedUntil(this.v4, NOW.plus(Duration.ofHours(2))).isEmpty(), "the block has ended");

		this.blocks.block(this.v4, NOW.plus(Duration.ofMinutes(5)), "again");
		assertEquals(NOW.plus(Duration.ofHours(1)), this.blocks.blockedUntil(this.v4, NOW).orElseThrow(), "a shorter block must not replace a longer one");

		this.blocks.block(this.v4, NOW.plus(Duration.ofHours(3)), "and again");
		assertEquals(NOW.plus(Duration.ofHours(3)), this.blocks.blockedUntil(this.v4, NOW).orElseThrow());
	}

	@Test
	void blocksCanBeLiftedAndExpiredOnesSwept() throws Exception {
		this.blocks.block(this.v4, NOW.minus(Duration.ofMinutes(1)), "old");
		this.blocks.block(this.v6, NOW.plus(Duration.ofHours(1)), "current");

		assertEquals(1, this.blocks.deleteExpired(NOW));
		assertTrue(this.blocks.unblock(this.v6));
		assertFalse(this.blocks.unblock(this.v6));
		assertTrue(this.blocks.blockedUntil(this.v6, NOW).isEmpty());
	}

	// ---- premium cache ----

	@Test
	void whatMojangSaidIsCachedIgnoringCaseIncludingThatAFreeNameIsFree() throws Exception {
		final UUID mojang = UUID.randomUUID();

		this.premiumCache.save("Notch", mojang, "mojang", NOW);
		this.premiumCache.save("FreeName", null, "ashcon", NOW);

		final PremiumCacheRepository.Entry notch = this.premiumCache.find("NOTCH").orElseThrow();

		assertEquals(mojang, notch.premiumUuid());
		assertEquals("mojang", notch.source());
		assertEquals(NOW, notch.checkedAt());
		assertEquals("notch", notch.username());

		assertNull(this.premiumCache.find("freename").orElseThrow().premiumUuid());
		assertTrue(this.premiumCache.find("unknown").isEmpty());
	}

	@Test
	void aNewAnswerReplacesAnOldOneAndOldAnswersAreSwept() throws Exception {
		this.premiumCache.save("Steve", null, "mojang", NOW.minus(Duration.ofDays(2)));
		this.premiumCache.save("Steve", UUID.randomUUID(), "ashcon", NOW);
		this.premiumCache.save("Old", null, "mojang", NOW.minus(Duration.ofDays(10)));

		assertEquals("ashcon", this.premiumCache.find("Steve").orElseThrow().source());
		assertEquals(1, this.premiumCache.deleteOlderThan(NOW.minus(Duration.ofDays(5))));
		assertTrue(this.premiumCache.find("Old").isEmpty());
	}

	// ---- audit ----

	@Test
	void auditEntriesAreStoredTruncatedAndSwept() throws Exception {
		this.audit.log(AuditRepository.Event.LOGIN_FAILED, UUID.randomUUID(), "Steve", this.v4, null, "velocity-1", "x".repeat(1000));
		this.audit.log(AuditRepository.Event.IMPORT, null, null, null, "CONSOLE", "velocity-1", null);

		assertEquals(2, this.schema.database().query(connection -> {
			try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM auth_audit")) {
				result.next();

				return result.getInt(1);
			}
		}).intValue());

		assertEquals(255, this.schema.database().query(connection -> {
			try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT MAX(CHAR_LENGTH(details)) FROM auth_audit")) {
				result.next();

				return result.getInt(1);
			}
		}).intValue());

		assertEquals(0, this.audit.deleteOlderThan(NOW.minus(Duration.ofDays(1))));
		assertEquals(2, this.audit.deleteOlderThan(NOW.plus(Duration.ofDays(1))));
	}
}
