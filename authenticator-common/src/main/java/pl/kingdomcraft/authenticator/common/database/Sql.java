package pl.kingdomcraft.authenticator.common.database;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Small JDBC helpers for nullable columns, UUIDs, IP addresses and UTC instants. */
public final class Sql {

	private Sql() {
	}

	public static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.OTHER);
		else
			statement.setObject(index, value);
	}

	public static void setString(PreparedStatement statement, int index, String value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.VARCHAR);
		else
			statement.setString(index, value);
	}

	/**
	 * Writes an instant as its UTC date and time. {@code setTimestamp} and {@code setObject(Instant)} must not be used: the MariaDB
	 * driver then writes the wall-clock time of the JVM's default time zone, whatever {@code connectionTimeZone} says, so on a
	 * machine that is not set to UTC the rows would hold the wrong time and disagree with columns the server fills in itself.
	 */
	public static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.TIMESTAMP);
		else
			statement.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
	}

	public static void setInt(PreparedStatement statement, int index, Integer value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.INTEGER);
		else
			statement.setInt(index, value);
	}

	/** Writes an address into an {@code INET6} column, see {@link #ipText}. */
	public static void setIp(PreparedStatement statement, int index, InetAddress value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.VARCHAR);
		else
			statement.setString(index, ipText(value));
	}

	/**
	 * The text form an {@code INET6} column accepts. MariaDB rejects plain IPv4 text there ({@code 1.2.3.4}), so IPv4 is written
	 * IPv4-mapped ({@code ::ffff:1.2.3.4}); that form is also what comparisons in SQL must use. Link-local scope ids are dropped.
	 */
	public static String ipText(InetAddress address) {
		final byte[] raw = address.getAddress();

		if (raw.length == 4)
			return "::ffff:" + (raw[0] & 0xff) + "." + (raw[1] & 0xff) + "." + (raw[2] & 0xff) + "." + (raw[3] & 0xff);

		try {
			return InetAddress.getByAddress(raw).getHostAddress();

		} catch (final UnknownHostException ex) {
			throw new IllegalArgumentException("Not an IP address", ex);
		}
	}

	public static UUID uuid(ResultSet result, String column) throws SQLException {
		return result.getObject(column, UUID.class);
	}

	/** Reads a {@code TIMESTAMP} or {@code DATETIME} column as the UTC instant it holds; see {@link #setInstant}. */
	public static Instant instant(ResultSet result, String column) throws SQLException {
		final LocalDateTime value = result.getObject(column, LocalDateTime.class);

		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}

	public static Integer integer(ResultSet result, String column) throws SQLException {
		final int value = result.getInt(column);

		return result.wasNull() ? null : value;
	}

	/** Reads an {@code INET6} column; an IPv4-mapped value comes back as an IPv4 address. */
	public static InetAddress ip(ResultSet result, String column) throws SQLException {
		final String text = result.getString(column);

		if (text == null)
			return null;

		try {
			return InetAddress.getByName(text); // literal address, never a DNS lookup

		} catch (final UnknownHostException ex) {
			throw new SQLException("Column " + column + " holds '" + text + "', which is not an IP address", ex);
		}
	}
}
