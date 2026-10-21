package pl.kingdomcraft.authenticator.common.migrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher;
import pl.kingdomcraft.authenticator.common.migrate.MineLoginImporter.ImportException;
import pl.kingdomcraft.authenticator.common.migrate.MineLoginImporter.Report;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings.MineLoginSource;
import pl.kingdomcraft.authenticator.common.support.TestMariaDb;

class MineLoginImporterIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("test");
	private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
	private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

	/**
	 * The table mineLogin 1.10 creates (JdbcQueries.CREATE_TABLE); LONG is MEDIUMTEXT in MariaDB, so times are stored as text. The username column is binary so that names differing only in case can
	 * coexist, as they can on a server whose default collation is case-sensitive.
	 */
	private static final String MINELOGIN_DDL = "CREATE TABLE mineLogin (username VARCHAR(36) COLLATE utf8mb4_bin NOT NULL, uuid VARCHAR(36), email VARCHAR(254), salt VARCHAR(64), paid VARCHAR(5) NOT NULL, "
			+ "password VARCHAR(255), hashType VARCHAR(32), registerAddress VARCHAR(64), lastJoinAddress VARCHAR(64), registerTime LONG, lastJoinTime LONG, sessionAddress VARCHAR(64), "
			+ "sessionTime LONG, sessionActive VARCHAR(5), lastAttempt VARCHAR(32), lastUpdate TIMESTAMP, secret TEXT, recoveryCodes TEXT, logged BOOLEAN, PRIMARY KEY (username))";

	// Made by independent implementations (Python's bcrypt, argon2-cffi and hashlib).
	private static final String BCRYPT = "$2b$06$rjGfgeL9RvskvOZYptY0lOYBluwW0p9wRX2YFfoNvYXfPUJNcWKcC"; // hunter2
	private static final String ARGON2I = "$argon2i$v=19$m=65536,t=4,p=1$xPKVM7H9iYoqTphzkvY5OA$4eQWkIIiUraWR7y2qsCfPvedHP5LdSn5WVxtsrZRjNQ"; // hunter2
	private static final String SHA256_SALTED = "fcf57a58b2120bbde39ee8cf358ae073c3305f0fb655eabc5068ab70213a2f55"; // hunter2 + Zk3pQ9
	private static final String SHA512_SALTED = "5e85320a1618166bf47ec7c1aa8dad4597818fa8ebce6f1f7170d78fff16ea135327a5e4e7b05004d96004a6807a6d2f1a52b57bf411d162a6a3d0e1386f93cf"; // hunter2 + Zk3pQ9
	private static final String SALT = "Zk3pQ9";

	private TestMariaDb.Schema schema;
	private AccountRepository accounts;
	private MineLoginImporter importer;
	private final PasswordHasher hasher = new PasswordHasher(new PasswordHasher.Settings(HashAlgo.BCRYPT, 4, 19_456, 2, 1));

	@BeforeEach
	void setUp() throws Exception {
		this.schema = TestMariaDb.createSchema(true);
		this.accounts = new AccountRepository(this.schema.database());
		this.importer = new MineLoginImporter(this.schema.database(), this.accounts, new AuditRepository(this.schema.database()), new MineLoginSource("", "mineLogin"), java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), LOGGER, "velocity-1");

		this.execute(MINELOGIN_DDL);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (this.schema != null)
			this.schema.close();
	}

	private void execute(String sql) throws SQLException {
		this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}

			return null;
		});
	}

	private void row(String username, String uuid, String salt, String paid, String password, String hashType, String registerAddress, String lastJoinAddress, String registerTime, String lastJoinTime)
			throws SQLException {
		this.row("mineLogin", username, uuid, salt, paid, password, hashType, registerAddress, lastJoinAddress, registerTime, lastJoinTime);
	}

	private void row(String table, String username, String uuid, String salt, String paid, String password, String hashType, String registerAddress, String lastJoinAddress, String registerTime,
			String lastJoinTime) throws SQLException {
		this.schema.database().query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + table
					+ " (username, uuid, salt, paid, password, hashType, registerAddress, lastJoinAddress, registerTime, lastJoinTime) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				final String[] values = { username, uuid, salt, paid, password, hashType, registerAddress, lastJoinAddress, registerTime, lastJoinTime };

				for (int i = 0; i < values.length; i++)
					statement.setString(i + 1, values[i]);

				statement.executeUpdate();
			}

			return null;
		});
	}

	private static String offlineUuid(String name) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
	}

	private void typicalNetwork() throws SQLException {
		this.row("Steve", offlineUuid("Steve"), null, "false", BCRYPT, "BCRYPT", "203.0.113.5", "2001:db8::5", "1700000000000", "1760000000123");
		this.row("Salty", offlineUuid("Salty"), SALT, "false", SHA256_SALTED, "SHA256", "203.0.113.6", "203.0.113.6", "1700000001000", "1760000001000");
		this.row("Argonian", offlineUuid("Argonian"), null, "false", ARGON2I, "ARGON2", null, null, "1700000002000", null);
		this.row("Notch", NOTCH.toString(), null, "true", BCRYPT, "BCRYPT", "203.0.113.9", "203.0.113.9", "1700000003000", "1760000003000");
		this.row("NoType", offlineUuid("NoType"), SALT, "false", SHA512_SALTED, null, null, null, "1700000004000", "1760000004000");
		this.row("NoUuid", null, null, "false", BCRYPT, "BCRYPT", null, null, "1700000005000", "1760000005000");
		this.row("HashedAddr", offlineUuid("HashedAddr"), null, "false", BCRYPT, "BCRYPT", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b", "9f86d081884c7d659a2feaa0c55ad015", "0", "not-a-number");
	}

	private void problemRows() throws SQLException {
		this.row("Legacy", offlineUuid("Legacy"), null, "false", "5f4dcc3b5aa765d61d8327deb882cf99", "MD5", null, null, "1", "1");
		this.row("Authme", offlineUuid("Authme"), "salt", "false", "$SHA$salt$" + "a".repeat(64), "AUTHME_SHA256", null, null, "1", "1");
		this.row("BadShape", offlineUuid("BadShape"), null, "false", "zzz", "BCRYPT", null, null, "1", "1");
		this.row("WrongKind", offlineUuid("WrongKind"), null, "false", SHA256_SALTED, "BCRYPT", null, null, "1", "1");
		this.row("NoPassword", offlineUuid("NoPassword"), null, "false", null, null, null, null, "1", "1");
		this.row(".Bedrock", offlineUuid(".Bedrock"), null, "false", BCRYPT, "BCRYPT", null, null, "1", "1");
		this.row("ab", offlineUuid("ab"), null, "false", BCRYPT, "BCRYPT", null, null, "1", "1");
		this.row("ThisNameIsFarTooLong", offlineUuid("ThisNameIsFarTooLong"), null, "false", BCRYPT, "BCRYPT", null, null, "1", "1");
		this.row("PremiumNoUuid", null, null, "true", null, null, null, null, "1", "1");
		this.row("DupUuidA", "11111111-1111-4111-8111-111111111111", null, "false", BCRYPT, "BCRYPT", null, null, "1", "1");
		this.row("DupUuidB", "11111111-1111-4111-8111-111111111111", null, "false", BCRYPT, "BCRYPT", null, null, "1", "1");
		this.row("Twin", offlineUuid("Twin"), null, "false", BCRYPT, "BCRYPT", null, null, "1", "1");
		this.row("TWIN", offlineUuid("TWIN"), null, "false", BCRYPT, "BCRYPT", null, null, "2", "2");
	}

	private Account account(String name) throws Exception {
		return this.accounts.findByUsername(name).orElseThrow();
	}

	// ---- the happy path ----

	@Test
	void everyKindOfAccountIsTakenOverAndItsPasswordKeepsWorking() throws Exception {
		this.typicalNetwork();

		final Report report = this.importer.run(false, "CONSOLE");

		assertEquals(7, report.read());
		assertEquals(7, report.imported());
		assertEquals(1, report.premium());
		assertEquals(6, report.nonPremium());
		assertEquals(0, report.skipped());
		assertEquals(0, report.alreadyHere());

		final Account steve = this.account("Steve");

		assertFalse(steve.premium());
		assertEquals(HashAlgo.BCRYPT, steve.hashAlgo());
		assertTrue(this.hasher.verify(steve.hashAlgo(), steve.passwordHash(), steve.passwordSalt(), "hunter2"));
		assertEquals(UUID.fromString(offlineUuid("Steve")), steve.uuid());
		assertEquals(InetAddress.getByName("203.0.113.5"), steve.registeredIp());
		assertEquals(InetAddress.getByName("2001:db8::5"), steve.lastLoginIp());
		assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), steve.registeredAt());
		assertEquals(Instant.ofEpochMilli(1_760_000_000_123L), steve.lastLoginAt());

		final Account salty = this.account("Salty");

		assertEquals(HashAlgo.SHA256, salty.hashAlgo());
		assertEquals(SALT, salty.passwordSalt());
		assertTrue(this.hasher.verify(salty.hashAlgo(), salty.passwordHash(), salty.passwordSalt(), "hunter2"), "the salt must travel with the hash");
		assertFalse(this.hasher.verify(salty.hashAlgo(), salty.passwordHash(), salty.passwordSalt(), "hunter3"));

		final Account argonian = this.account("Argonian");

		assertEquals(HashAlgo.ARGON2, argonian.hashAlgo());
		assertTrue(this.hasher.verify(argonian.hashAlgo(), argonian.passwordHash(), argonian.passwordSalt(), "hunter2"));
		assertNull(argonian.registeredIp());
		assertNull(argonian.lastLoginAt());

		final Account notch = this.account("Notch");

		assertTrue(notch.premium());
		assertEquals(NOTCH, notch.premiumUuid());
		assertEquals(NOTCH, notch.uuid());
		assertFalse(notch.hasPassword(), "an original account needs no password");
		assertNull(notch.hashAlgo());

		final Account noType = this.account("NoType");

		assertEquals(HashAlgo.SHA512, noType.hashAlgo(), "without a type the shape of the hash decides");
		assertTrue(this.hasher.verify(noType.hashAlgo(), noType.passwordHash(), noType.passwordSalt(), "hunter2"));

		assertEquals(UUID.fromString(offlineUuid("NoUuid")), this.account("NoUuid").uuid(), "a name without a UUID gets the one Minecraft derives for it");
	}

	@Test
	void addressesThatAreNotAddressesAndTimesThatAreNotTimesBecomeNothing() throws Exception {
		this.typicalNetwork();
		this.importer.run(false, "CONSOLE");

		final Account hashed = this.account("HashedAddr");

		assertNull(hashed.registeredIp(), "mineLogin can store a hash of the address; it cannot be turned back into one");
		assertNull(hashed.lastLoginIp());
		assertNull(hashed.lastLoginAt(), "an unreadable time is nothing, not an error");
		assertTrue(Duration.between(hashed.registeredAt(), Instant.now()).abs().compareTo(Duration.ofMinutes(5)) < 0, "a zero registration time falls back to the import time: " + hashed.registeredAt());
	}

	// ---- what is left out, and why ----

	@Test
	void accountsThatCannotBeTakenOverAreSkippedAndExplained() throws Exception {
		this.problemRows();

		final Report report = this.importer.run(false, "CONSOLE");

		assertEquals(13, report.read());
		assertEquals(2, report.imported(), "DupUuidA, Twin and nothing else; DupUuidB conflicts and TWIN is the same name as Twin");
		assertEquals(5, report.unsupportedHash(), "MD5, AuthMe, a hash that is not one, a hash of another kind than its type, no password");
		assertEquals(3, report.invalidName());
		assertEquals(1, report.invalidUuid());
		assertEquals(1, report.uuidConflict());
		assertEquals(1, report.alreadyHere(), "TWIN: names are unique ignoring case here, but not in mineLogin");

		assertTrue(this.accounts.findByUsername("Legacy").isEmpty());
		assertTrue(this.accounts.findByUsername("Authme").isEmpty());
		assertTrue(this.accounts.findByUsername("BadShape").isEmpty());
		assertTrue(this.accounts.findByUsername("WrongKind").isEmpty());
		assertTrue(this.accounts.findByUsername(".Bedrock").isEmpty());
		assertTrue(this.accounts.findByUsername("DupUuidB").isEmpty());
		assertEquals("Twin", this.account("twin").username(), "the first of two names that differ only in case wins");

		assertFalse(report.examples().isEmpty());
		assertTrue(report.examples().stream().anyMatch(example -> example.startsWith("Legacy:") && example.contains("MD5")), report.examples().toString());
		assertTrue(report.examples().stream().anyMatch(example -> example.startsWith(".Bedrock:")), report.examples().toString());
		assertTrue(report.examples().stream().anyMatch(example -> example.startsWith("DupUuidB:") && example.contains("11111111-1111-4111-8111-111111111111")), report.examples().toString());
	}

	@Test
	void theExamplesAreCapped() throws Exception {
		for (int i = 0; i < 60; i++)
			this.row("Bad" + i, offlineUuid("Bad" + i), null, "false", "5f4dcc3b5aa765d61d8327deb882cf99", "MD5", null, null, "1", "1");

		final Report report = this.importer.run(true, "CONSOLE");

		assertEquals(60, report.unsupportedHash());
		assertEquals(20, report.examples().size());
	}

	// ---- dry run, repeating, coexisting ----

	@Test
	void aDryRunReportsExactlyWhatARealRunDoesAndWritesNothing() throws Exception {
		this.typicalNetwork();
		this.problemRows();

		final Report dry = this.importer.run(true, "CONSOLE");

		assertTrue(dry.dryRun());
		assertEquals(0, this.accounts.count(), "a dry run must not write");
		assertEquals(0, this.audit(), "nor audit");

		final Report real = this.importer.run(false, "CONSOLE");

		assertEquals(dry.read(), real.read());
		assertEquals(dry.imported(), real.imported());
		assertEquals(dry.premium(), real.premium());
		assertEquals(dry.nonPremium(), real.nonPremium());
		assertEquals(dry.skipped(), real.skipped());
		assertEquals(dry.alreadyHere(), real.alreadyHere());
		assertEquals(dry.examples(), real.examples());
		assertEquals(real.imported(), this.accounts.count());
	}

	@Test
	void runningAgainChangesNothing() throws Exception {
		this.typicalNetwork();

		this.importer.run(false, "CONSOLE");

		final String before = this.account("Steve").passwordHash();
		final long version = this.account("Steve").version();
		final Report again = this.importer.run(false, "CONSOLE");

		assertEquals(0, again.imported());
		assertEquals(7, again.alreadyHere());
		assertEquals(7, this.accounts.count());
		assertEquals(before, this.account("Steve").passwordHash());
		assertEquals(version, this.account("Steve").version());
	}

	@Test
	void anAccountThatAlreadyExistsHereIsNeverOverwritten() throws Exception {
		this.typicalNetwork();
		this.accounts.insert(new NewAccount(UUID.randomUUID(), "Steve", false, null, this.hasher.hash("new-password-here").hash(), null, HashAlgo.BCRYPT, null));

		final Report report = this.importer.run(false, "CONSOLE");

		assertEquals(6, report.imported());
		assertEquals(1, report.alreadyHere());
		assertTrue(this.hasher.verify(HashAlgo.BCRYPT, this.account("Steve").passwordHash(), null, "new-password-here"), "a player who registered here keeps their new password");
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, this.account("Steve").passwordHash(), null, "hunter2"));
	}

	@Test
	void anAccountWhoseUuidIsTakenHereIsReportedNotForced() throws Exception {
		this.typicalNetwork();
		this.accounts.insert(new NewAccount(UUID.fromString(offlineUuid("Steve")), "SomeoneElse", false, null, "$2a$04$h", null, HashAlgo.BCRYPT, null));

		final Report report = this.importer.run(false, "CONSOLE");

		assertEquals(1, report.uuidConflict());
		assertTrue(this.accounts.findByUsername("Steve").isEmpty());
	}

	@Test
	void theMineLoginTableIsNeverChanged() throws Exception {
		this.typicalNetwork();

		final int before = this.count("SELECT COUNT(*) FROM mineLogin");
		final String checksum = this.text("SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', username, uuid, password, paid) ORDER BY username)) FROM mineLogin");

		this.importer.run(false, "CONSOLE");

		assertEquals(before, this.count("SELECT COUNT(*) FROM mineLogin"));
		assertEquals(checksum, this.text("SELECT MD5(GROUP_CONCAT(CONCAT_WS('|', username, uuid, password, paid) ORDER BY username)) FROM mineLogin"));
	}

	@Test
	void aRealRunLeavesAnAuditEntryWithTheActor() throws Exception {
		this.typicalNetwork();
		this.importer.run(false, "Admin");

		assertEquals(1, this.count("SELECT COUNT(*) FROM auth_audit WHERE event = 'IMPORT' AND actor = 'Admin' AND details LIKE 'read 7, imported 7%'"));
	}

	@Test
	void anEmptyTableImportsNothing() throws Exception {
		final Report report = this.importer.run(false, "CONSOLE");

		assertEquals(0, report.read());
		assertEquals(0, report.imported());
	}

	@Test
	void aLargeTableIsImportedInBatches() throws Exception {
		this.schema.database().query(connection -> {
			try (PreparedStatement statement = connection.prepareStatement("INSERT INTO mineLogin (username, uuid, paid, password, hashType, registerTime) VALUES (?, ?, 'false', ?, 'BCRYPT', '1700000000000')")) {
				for (int i = 0; i < 1_300; i++) {
					statement.setString(1, "Player" + i);
					statement.setString(2, offlineUuid("Player" + i));
					statement.setString(3, BCRYPT);
					statement.addBatch();
				}

				statement.executeBatch();
			}

			return null;
		});

		final Report report = this.importer.run(false, "CONSOLE");

		assertEquals(1_300, report.imported());
		assertEquals(1_300, this.accounts.count());
		assertEquals(0, this.importer.run(false, "CONSOLE").imported());
	}

	// ---- where the table is ----

	@Test
	void theTableCanBeInAnotherDatabaseOfTheSameServer() throws Exception {
		final String other = "authtest_ml_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

		try {
			this.execute("CREATE DATABASE " + other);
			this.execute(MINELOGIN_DDL.replace("CREATE TABLE mineLogin", "CREATE TABLE " + other + ".legacy_users"));
			this.row(other + ".legacy_users", "Steve", offlineUuid("Steve"), null, "false", BCRYPT, "BCRYPT", null, null, "1700000000000", "1760000000000");

			final MineLoginImporter foreign = new MineLoginImporter(this.schema.database(), this.accounts, new AuditRepository(this.schema.database()), new MineLoginSource(other, "legacy_users"),
					java.time.Clock.systemUTC(), LOGGER, "velocity-1");

			assertEquals(1, foreign.run(false, "CONSOLE").imported());
			assertNotNull(this.account("Steve"));

		} finally {
			this.execute("DROP DATABASE IF EXISTS " + other);
		}
	}

	@Test
	void aMissingTableIsReportedWithTheSettingToChange() throws Exception {
		final MineLoginImporter missing = new MineLoginImporter(this.schema.database(), this.accounts, new AuditRepository(this.schema.database()), new MineLoginSource("", "no_such_table"),
				java.time.Clock.systemUTC(), LOGGER, "velocity-1");

		final ImportException ex = assertThrows(ImportException.class, () -> missing.run(false, "CONSOLE"));

		assertTrue(ex.getMessage().contains("`no_such_table`"), ex.getMessage());
		assertTrue(ex.getMessage().contains("migration.minelogin"), ex.getMessage());
	}

	// ---- parsing helpers ----

	@Test
	void addressesAreParsedStrictlyAndNeverLookedUp() {
		assertEquals("/203.0.113.5", String.valueOf(MineLoginImporter.address("203.0.113.5")));
		assertEquals(InetAddressOf("2001:db8::1"), MineLoginImporter.address("2001:db8::1"));
		assertEquals(InetAddressOf("::ffff:1.2.3.4"), MineLoginImporter.address(" ::ffff:1.2.3.4 "));

		for (final String bad : new String[] { null, "", "localhost", "example.com", "999.1.1.1", "1.2.3", "1.2.3.4.5", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b", "gggg::1", "1.2.3.4; DROP", "::::" })
			assertNull(MineLoginImporter.address(bad), String.valueOf(bad));
	}

	private static InetAddress InetAddressOf(String literal) {
		try {
			return InetAddress.getByName(literal);
		} catch (final Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void timesAreEpochMillisecondsOrNothing() {
		assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), MineLoginImporter.instant("1700000000000"));
		assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), MineLoginImporter.instant(" 1700000000000 "));

		for (final String bad : new String[] { null, "", "0", "-5", "abc", "1.5", "99999999999999", "17000000000000000000000" })
			assertNull(MineLoginImporter.instant(bad), String.valueOf(bad));
	}

	private int count(String sql) throws SQLException {
		return this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
				result.next();

				return result.getInt(1);
			}
		});
	}

	private int audit() throws SQLException {
		return this.count("SELECT COUNT(*) FROM auth_audit");
	}

	private String text(String sql) throws SQLException {
		return this.schema.database().query(connection -> {
			try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
				result.next();

				return result.getString(1);
			}
		});
	}
}
