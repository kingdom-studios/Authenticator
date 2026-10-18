package pl.kingdomcraft.authenticator.common.migrate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.database.Database;
import pl.kingdomcraft.authenticator.common.hash.HashAlgo;
import pl.kingdomcraft.authenticator.common.premium.HttpPremiumApi;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository;
import pl.kingdomcraft.authenticator.common.repository.AccountRepository.NewAccount;
import pl.kingdomcraft.authenticator.common.repository.AuditRepository;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings.MineLoginSource;

/**
 * Copies the accounts of mineLogin into Authenticator. It only reads the mineLogin table and never changes or removes anything in it, so
 * mineLogin keeps working beside it and the import can be run again: accounts that already exist here are left alone, which also means
 * players who registered here meanwhile are never overwritten.
 *
 * <p>What is taken over: name, UUID, premium or not, the password hash with its salt (BCrypt, Argon2, SHA-256 and SHA-512; the player's
 * next login replaces it with a current one), addresses and times. What is not: e-mail, two-factor secrets, recovery codes, sessions.
 * An account whose hash is of a kind we cannot verify is skipped and reported, so its owner registers again instead of being locked out
 * of an account nobody can open.
 *
 * <p>Blocking and potentially long: run it on the database executor, never on a thread that must not wait.
 */
public final class MineLoginImporter {

	/** The kinds of account that were not imported, with the first few examples so an admin can see what is going on. */
	public record Report(boolean dryRun, int read, int imported, int premium, int nonPremium, int alreadyHere, int invalidName, int unsupportedHash, int invalidUuid, int uuidConflict,
			List<String> examples) {

		/** Accounts that would be (dry run) or were (real run) left out for a reason other than already being here. */
		public int skipped() {
			return this.invalidName + this.unsupportedHash + this.invalidUuid + this.uuidConflict;
		}
	}

	/** The mineLogin table cannot be read: it does not exist, or the database user may not read it. */
	public static final class ImportException extends Exception {
		private static final long serialVersionUID = 1L;

		public ImportException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private static final int BATCH = 500;
	private static final int MAX_EXAMPLES = 20;
	private static final int TABLE_DOES_NOT_EXIST = 1146;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
	private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]{2,45}");

	private final Database database;
	private final AccountRepository accounts;
	private final AuditRepository audit;
	private final MineLoginSource source;
	private final Clock clock;
	private final Logger logger;
	private final String nodeId;

	public MineLoginImporter(Database database, AccountRepository accounts, AuditRepository audit, MineLoginSource source, Clock clock, Logger logger, String nodeId) {
		this.database = database;
		this.accounts = accounts;
		this.audit = audit;
		this.source = source;
		this.clock = clock;
		this.logger = logger;
		this.nodeId = nodeId;
	}

	/**
	 * @param dryRun true to only count what would happen
	 * @param actor  who asked, for the audit trail (an admin's name or CONSOLE)
	 */
	public Report run(boolean dryRun, String actor) throws ImportException, SQLException {
		final List<SourceRow> rows = this.readRows();

		// Names that differ only in case can coexist in mineLogin when its column is case-sensitive, but not here: the player who registered first keeps the name. The order must not
		// depend on how the database happens to scan the table, or a dry run and the real run could disagree.
		rows.sort(Comparator.comparing((SourceRow row) -> registeredAt(row.registerTime())).thenComparing(row -> row.username() == null ? "" : row.username()));

		final Set<String> names = new HashSet<>();
		final Set<UUID> uuids = new HashSet<>();
		final Set<UUID> premiumUuids = new HashSet<>();

		this.database.query(connection -> {
			try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT username_lower, uuid, premium_uuid FROM auth_accounts")) {
				while (result.next()) {
					names.add(result.getString("username_lower"));
					uuids.add(result.getObject("uuid", UUID.class));

					final UUID premiumUuid = result.getObject("premium_uuid", UUID.class);

					if (premiumUuid != null)
						premiumUuids.add(premiumUuid);
				}
			}

			return null;
		});

		final Counters counters = new Counters();
		final List<NewAccount> batch = new ArrayList<>();
		int inserted = 0;

		for (final SourceRow row : rows) {
			counters.read++;

			final NewAccount account = this.convert(row, names, uuids, premiumUuids, counters);

			if (account == null)
				continue;

			names.add(Account.key(account.username()));
			uuids.add(account.uuid());

			if (account.premiumUuid() != null)
				premiumUuids.add(account.premiumUuid());

			if (account.premium())
				counters.premium++;
			else
				counters.nonPremium++;

			batch.add(account);

			if (!dryRun && batch.size() >= BATCH) {
				inserted += this.accounts.insertIgnoringDuplicates(batch);
				batch.clear();
			}
		}

		if (!dryRun && !batch.isEmpty())
			inserted += this.accounts.insertIgnoringDuplicates(batch);

		final int wouldImport = counters.premium + counters.nonPremium;

		// A row that vanished between the check and the insert (a player registered meanwhile) was skipped by the database.
		final int imported = dryRun ? wouldImport : inserted;
		final int alreadyHere = counters.alreadyHere + (dryRun ? 0 : wouldImport - inserted);

		final Report report = new Report(dryRun, counters.read, imported, counters.premium, counters.nonPremium, alreadyHere, counters.invalidName, counters.unsupportedHash, counters.invalidUuid,
				counters.uuidConflict, List.copyOf(counters.examples));

		if (!dryRun)
			try {
				this.audit.log(AuditRepository.Event.IMPORT, null, null, null, actor, this.nodeId,
						"read " + report.read() + ", imported " + report.imported() + ", here already " + report.alreadyHere() + ", skipped " + report.skipped());

			} catch (final SQLException ex) {
				this.logger.log(Level.WARNING, "Could not write the import to the audit trail: " + ex.getMessage());
			}

		return report;
	}

	private record SourceRow(String username, String uuid, String salt, String paid, String password, String hashType, String registerAddress, String lastJoinAddress, String registerTime,
			String lastJoinTime) {
	}

	private static final class Counters {
		int read;
		int premium;
		int nonPremium;
		int alreadyHere;
		int invalidName;
		int unsupportedHash;
		int invalidUuid;
		int uuidConflict;
		final List<String> examples = new ArrayList<>();

		void example(String name, String reason) {
			if (this.examples.size() < MAX_EXAMPLES)
				this.examples.add(name + ": " + reason);
		}
	}

	private List<SourceRow> readRows() throws ImportException, SQLException {
		final String sql = "SELECT username, uuid, salt, paid, password, hashType, registerAddress, lastJoinAddress, registerTime, lastJoinTime FROM " + this.source.qualifiedName();

		try {
			return this.database.query(connection -> {
				final List<SourceRow> rows = new ArrayList<>();

				try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
					while (result.next())
						rows.add(new SourceRow(result.getString("username"), result.getString("uuid"), result.getString("salt"), result.getString("paid"), result.getString("password"),
								result.getString("hashType"), result.getString("registerAddress"), result.getString("lastJoinAddress"), result.getString("registerTime"),
								result.getString("lastJoinTime")));
				}

				return rows;
			});

		} catch (final SQLException ex) {
			if (ex.getErrorCode() == TABLE_DOES_NOT_EXIST)
				throw new ImportException("the table " + this.source.qualifiedName() + " does not exist. Check migration.minelogin.schema and table in config.yml.", ex);

			if (ex.getSQLState() != null && ex.getSQLState().startsWith("42") || ex.getMessage() != null && ex.getMessage().contains("denied"))
				throw new ImportException("could not read " + this.source.qualifiedName() + ": " + ex.getMessage() + ". The database user needs SELECT on it.", ex);

			throw ex;
		}
	}

	/** @return the account to create, or null with the reason counted */
	private NewAccount convert(SourceRow row, Set<String> names, Set<UUID> uuids, Set<UUID> premiumUuids, Counters counters) {
		final String name = row.username() == null ? "" : row.username().trim();

		if (!NAME.matcher(name).matches()) {
			counters.invalidName++;
			counters.example(name.isEmpty() ? "(empty)" : name, "not a name Authenticator accepts (3-16 letters, digits or _; Bedrock names start with a dot)");

			return null;
		}

		if (names.contains(Account.key(name))) {
			counters.alreadyHere++;

			return null;
		}

		final boolean premium = "true".equalsIgnoreCase(row.paid() == null ? "" : row.paid().trim()) || "1".equals(row.paid());
		UUID uuid = row.uuid() == null || row.uuid().isBlank() ? null : HttpPremiumApi.parseUuid(row.uuid().trim());

		if (uuid == null) {
			if (premium) {
				counters.invalidUuid++;
				counters.example(name, "premium account without a valid UUID");

				return null;
			}

			uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); // what Minecraft derives for a name without an account
		}

		if (uuids.contains(uuid) || premium && premiumUuids.contains(uuid)) {
			counters.uuidConflict++;
			counters.example(name, "its UUID " + uuid + " already belongs to another account");

			return null;
		}

		final InetAddress registerAddress = address(row.registerAddress());
		final Instant registeredAt = instant(row.registerTime());
		final Instant lastLoginAt = instant(row.lastJoinTime());
		final InetAddress lastLoginAddress = address(row.lastJoinAddress());

		if (premium)
			return new NewAccount(uuid, name, true, uuid, null, null, null, registerAddress, registeredAt, lastLoginAt, lastLoginAddress);

		final HashAlgo algorithm = algorithm(row);

		if (algorithm == null) {
			counters.unsupportedHash++;
			counters.example(name, "password hash of a kind that cannot be verified (type " + (row.hashType() == null || row.hashType().isBlank() ? "unknown" : row.hashType()) + "); the player must register again");

			return null;
		}

		final String salt = row.salt() == null || row.salt().isEmpty() ? null : row.salt();

		return new NewAccount(uuid, name, false, null, row.password().trim(), salt, algorithm, registerAddress, registeredAt, lastLoginAt, lastLoginAddress);
	}

	/** @return the algorithm of the row's password if it is one we verify and the hash looks like it, else null */
	private static HashAlgo algorithm(SourceRow row) {
		if (row.password() == null || row.password().isBlank())
			return null;

		final String hash = row.password().trim();
		final HashAlgo shape = HashAlgo.detect(hash);
		final HashAlgo named = HashAlgo.parse(row.hashType());

		// The named type decides when there is one; a hash that does not look like its type is not trusted.
		final HashAlgo algorithm = row.hashType() == null || row.hashType().isBlank() || "UNKNOWN".equalsIgnoreCase(row.hashType().trim()) ? shape : named;

		if (algorithm == null || shape == null)
			return null;

		final boolean consistent = switch (algorithm) {
			case BCRYPT -> shape == HashAlgo.BCRYPT;
			case ARGON2, ARGON2ID -> shape == HashAlgo.ARGON2 || shape == HashAlgo.ARGON2ID;
			case SHA256 -> shape == HashAlgo.SHA256;
			case SHA512 -> shape == HashAlgo.SHA512;
		};

		if (!consistent)
			return null;

		// "ARGON2" in mineLogin means any Argon2 variant; ours distinguishes the one we write ourselves.
		return algorithm == HashAlgo.ARGON2ID || algorithm == HashAlgo.ARGON2 ? (shape == HashAlgo.ARGON2ID ? HashAlgo.ARGON2ID : HashAlgo.ARGON2) : algorithm;
	}

	/** An IP address written as a literal, or null: mineLogin can store a hash instead, and a name must never be looked up. */
	static InetAddress address(String text) {
		if (text == null)
			return null;

		final String trimmed = text.trim();

		if (!IPV4.matcher(trimmed).matches() && !(trimmed.contains(":") && IPV6.matcher(trimmed).matches()))
			return null;

		try {
			return InetAddress.getByName(trimmed); // a literal, so no lookup takes place

		} catch (final UnknownHostException ex) {
			return null;
		}
	}

	/** Rows without a usable registration time sort last. */
	private static Instant registeredAt(String text) {
		final Instant instant = instant(text);

		return instant == null ? Instant.MAX : instant;
	}

	/** Epoch milliseconds, as mineLogin stores them (in a text column); null for a missing, zero, negative or unreadable value. */
	static Instant instant(String text) {
		if (text == null || text.isBlank())
			return null;

		try {
			final long millis = Long.parseLong(text.trim());

			return millis <= 0 || millis > 4_102_444_800_000L ? null : Instant.ofEpochMilli(millis); // not before 1970, not after 2100
		} catch (final NumberFormatException ex) {
			return null;
		}
	}

	/** Which names the report counts, for the log line. */
	public static String describe(Report report) {
		return String.format(Locale.ROOT, "%s: read %d, %s %d (%d premium, %d non-premium), already here %d, skipped %d (name %d, hash %d, uuid %d, uuid conflict %d)", report.dryRun() ? "dry run" : "import",
				report.read(), report.dryRun() ? "would import" : "imported", report.imported(), report.premium(), report.nonPremium(), report.alreadyHere(), report.skipped(), report.invalidName(),
				report.unsupportedHash(), report.invalidUuid(), report.uuidConflict());
	}
}
