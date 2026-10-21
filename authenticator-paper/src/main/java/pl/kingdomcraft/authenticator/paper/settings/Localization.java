package pl.kingdomcraft.authenticator.paper.settings;

import org.mineacademy.fo.settings.SimpleLocalization;

/**
 * localization/messages_LOCALE.yml: what staff and frozen players read on this server. Loaded by Foundation like {@link Settings}; the
 * defaults here apply to keys missing from the file, so an older messages file keeps working after an update. Values use Foundation's
 * {@code &} colours and {@code {placeholders}}.
 */
public final class Localization extends SimpleLocalization {

	@Override
	protected int getConfigVersion() {
		return 1;
	}

	/** What a player who has not logged in is told. */
	public static final class Gate {

		public static String LOGIN_FIRST = "&cLog in first.";
		public static String STARTING = "&cThe server is still starting. Try again in a moment.";
		public static String REFUSED_NOT_LOGGED_IN = "&cYou have not logged in. Join through the proxy and log in.";
		public static String REFUSED_UNKNOWN = "&cJoin the network through its main address.";
		public static String REFUSED_DATABASE = "&cYour login cannot be checked right now. Try again in a moment.";

		private static void init() {
			setPathPrefix("Gate");

			LOGIN_FIRST = read("Login_First", LOGIN_FIRST);
			STARTING = read("Starting", STARTING);
			REFUSED_NOT_LOGGED_IN = read("Refused_Not_Logged_In", REFUSED_NOT_LOGGED_IN);
			REFUSED_UNKNOWN = read("Refused_Unknown", REFUSED_UNKNOWN);
			REFUSED_DATABASE = read("Refused_Database", REFUSED_DATABASE);
		}
	}

	/** What the {@code /auth} commands say. */
	public static final class Admin {

		public static String STARTING = "&cAuthenticator is still starting, or failed to start (see the console). Try again in a moment.";
		public static String NO_SUCH_ACCOUNT = "&c{player} has no account.";
		public static String UNREGISTERED = "&aRemoved the account of &f{player}&a.";
		public static String PASSWORD_CHANGED = "&aChanged the password of &f{player}&a. Other addresses must type it again.";
		public static String FORCE_LOGIN_DONE = "&a{player} is being logged in.";
		public static String FORCE_LOGOUT_DONE = "&a{player} has to log in again.";
		public static String SET_PREMIUM_DONE = "&a{player} is now an original account. They must reconnect.";
		public static String SET_CRACKED_DONE = "&a{player} now has a password. They must reconnect and type it.";
		public static String PREMIUM_ACCOUNT = "&c{player} has an original account, which has no password. Use /auth setcracked first.";
		public static String NOT_PREMIUM_ACCOUNT = "&c{player} has a password account. Use /auth setpremium instead.";
		public static String NOT_ONLINE = "&c{player} is not connected to the network.";
		public static String ALREADY_LOGGED_IN = "&c{player} is already logged in.";
		public static String NOT_LOGGED_IN = "&c{player} is not logged in.";
		public static String LOOKUP_FAILED = "&cNo lookup service answered. Try again in a moment.";
		public static String NOT_ORIGINAL_NAME = "&cMojang knows no original account named {player}.";
		public static String CONFLICT = "&cThe account changed while the command ran, or the Mojang account belongs to another one. Check with /auth info and try again.";
		public static String ERROR = "&cSomething went wrong, see the console.";
		public static String WEAK_TOO_SHORT = "&cThe password is too short (at least {min} characters).";
		public static String WEAK_TOO_LONG = "&cThe password is too long (at most 72 bytes).";
		public static String WEAK_WHITESPACE = "&cThe password cannot contain spaces.";
		public static String WEAK_SAME_AS_NAME = "&cThe password cannot be the same as the name.";
		public static String WEAK_FORBIDDEN = "&cThat password is too common.";
		public static String PROXY_TOLD = "&7The proxy was told at once.";
		public static String PROXY_NOT_TOLD = "&7The proxy will notice within a few seconds.";
		public static String INFO_HEADER = "&6Account &f{player}";
		public static String INFO_LINE = "&7{key}: &f{value}";
		public static String MIGRATE_STARTED = "&7Reading the mineLogin accounts (they are never changed)...";
		public static String MIGRATE_BUSY = "&cAn import is already running.";
		public static String MIGRATE_FAILED = "&cImport failed: {error}";
		public static String MIGRATE_SKIPPED = "&e  skipped {example}";
		public static String STATUS_LINE = "&7{line}";

		private static void init() {
			setPathPrefix("Admin");

			STARTING = read("Starting", STARTING);
			NO_SUCH_ACCOUNT = read("No_Such_Account", NO_SUCH_ACCOUNT);
			UNREGISTERED = read("Unregistered", UNREGISTERED);
			PASSWORD_CHANGED = read("Password_Changed", PASSWORD_CHANGED);
			FORCE_LOGIN_DONE = read("Force_Login_Done", FORCE_LOGIN_DONE);
			FORCE_LOGOUT_DONE = read("Force_Logout_Done", FORCE_LOGOUT_DONE);
			SET_PREMIUM_DONE = read("Set_Premium_Done", SET_PREMIUM_DONE);
			SET_CRACKED_DONE = read("Set_Cracked_Done", SET_CRACKED_DONE);
			PREMIUM_ACCOUNT = read("Premium_Account", PREMIUM_ACCOUNT);
			NOT_PREMIUM_ACCOUNT = read("Not_Premium_Account", NOT_PREMIUM_ACCOUNT);
			NOT_ONLINE = read("Not_Online", NOT_ONLINE);
			ALREADY_LOGGED_IN = read("Already_Logged_In", ALREADY_LOGGED_IN);
			NOT_LOGGED_IN = read("Not_Logged_In", NOT_LOGGED_IN);
			LOOKUP_FAILED = read("Lookup_Failed", LOOKUP_FAILED);
			NOT_ORIGINAL_NAME = read("Not_Original_Name", NOT_ORIGINAL_NAME);
			CONFLICT = read("Conflict", CONFLICT);
			ERROR = read("Error", ERROR);
			WEAK_TOO_SHORT = read("Weak_Too_Short", WEAK_TOO_SHORT);
			WEAK_TOO_LONG = read("Weak_Too_Long", WEAK_TOO_LONG);
			WEAK_WHITESPACE = read("Weak_Whitespace", WEAK_WHITESPACE);
			WEAK_SAME_AS_NAME = read("Weak_Same_As_Name", WEAK_SAME_AS_NAME);
			WEAK_FORBIDDEN = read("Weak_Forbidden", WEAK_FORBIDDEN);
			PROXY_TOLD = read("Proxy_Told", PROXY_TOLD);
			PROXY_NOT_TOLD = read("Proxy_Not_Told", PROXY_NOT_TOLD);
			INFO_HEADER = read("Info_Header", INFO_HEADER);
			INFO_LINE = read("Info_Line", INFO_LINE);
			MIGRATE_STARTED = read("Migrate_Started", MIGRATE_STARTED);
			MIGRATE_BUSY = read("Migrate_Busy", MIGRATE_BUSY);
			MIGRATE_FAILED = read("Migrate_Failed", MIGRATE_FAILED);
			MIGRATE_SKIPPED = read("Migrate_Skipped", MIGRATE_SKIPPED);
			STATUS_LINE = read("Status_Line", STATUS_LINE);
		}
	}

	/** What the placeholders show when there is nothing better. */
	public static final class Placeholders {

		public static String LOADING = "loading";
		public static String TRUE_TEXT = "true";
		public static String FALSE_TEXT = "false";
		public static String STATE_UNKNOWN = "unknown";
		public static String STATE_PENDING_LOGIN = "pending_login";
		public static String STATE_PENDING_REGISTER = "pending_register";
		public static String STATE_AUTHENTICATED = "authenticated";

		private static void init() {
			setPathPrefix("Placeholders");

			LOADING = read("Loading", LOADING);
			TRUE_TEXT = read("True_Text", TRUE_TEXT);
			FALSE_TEXT = read("False_Text", FALSE_TEXT);
			STATE_UNKNOWN = read("State_Unknown", STATE_UNKNOWN);
			STATE_PENDING_LOGIN = read("State_Pending_Login", STATE_PENDING_LOGIN);
			STATE_PENDING_REGISTER = read("State_Pending_Register", STATE_PENDING_REGISTER);
			STATE_AUTHENTICATED = read("State_Authenticated", STATE_AUTHENTICATED);
		}
	}

	/** The value of the key under the current path prefix, or the default if the file lacks it. */
	private static String read(String key, String defaultValue) {
		return isSetDefault(key) ? getString(key) : defaultValue;
	}
}
