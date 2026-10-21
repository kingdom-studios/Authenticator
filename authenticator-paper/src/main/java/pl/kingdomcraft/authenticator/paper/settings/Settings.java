package pl.kingdomcraft.authenticator.paper.settings;

import java.time.Duration;
import java.util.Locale;

import org.mineacademy.fo.Valid;
import org.mineacademy.fo.settings.SimpleSettings;

import pl.kingdomcraft.authenticator.common.gate.JoinVerifier;

/**
 * settings.yml. Foundation loads this class (it extends {@link SimpleSettings}) before the plugin starts and again on reload; every
 * {@code private static void init()} below is invoked by reflection. Fields are boxed types so that they are never left null.
 *
 * <p>This holds what only a Paper server has. The node, the database, the bridge and the admin command settings are shared with the proxy
 * and are read by {@code BackendSettings} in authenticator-common, from the same file.
 */
public final class Settings extends SimpleSettings {

	@Override
	protected int getConfigVersion() {
		return 1;
	}

	public static final class Server {

		public static JoinVerifier.Role ROLE;

		private static void init() {
			setPathPrefix("Server");

			final String role = getString("Role").trim().toUpperCase(Locale.ROOT);

			try {
				ROLE = JoinVerifier.Role.valueOf(role);

			} catch (final IllegalArgumentException ex) {
				throw new IllegalArgumentException("Server.Role in settings.yml must be AUTH or GAME, not '" + role + "'");
			}
		}
	}

	public static final class Gate {

		public static Integer POLL_SECONDS;
		public static Boolean HIDE_UNAUTHENTICATED;
		public static Integer MESSAGE_COOLDOWN_SECONDS;

		private static void init() {
			setPathPrefix("Gate");

			POLL_SECONDS = getInteger("Poll_Seconds");
			HIDE_UNAUTHENTICATED = getBoolean("Hide_Unauthenticated");
			MESSAGE_COOLDOWN_SECONDS = getInteger("Message_Cooldown_Seconds");

			Valid.checkBoolean(POLL_SECONDS >= 1 && POLL_SECONDS <= 60, "Gate.Poll_Seconds in settings.yml must be between 1 and 60");
			Valid.checkBoolean(MESSAGE_COOLDOWN_SECONDS >= 0, "Gate.Message_Cooldown_Seconds in settings.yml cannot be negative");
		}

		public static Duration poll() {
			return Duration.ofSeconds(POLL_SECONDS);
		}
	}

	public static final class LoginSpawn {

		public static String WORLD;
		public static Double X;
		public static Double Y;
		public static Double Z;
		public static Double YAW;
		public static Double PITCH;

		private static void init() {
			setPathPrefix("Login_Spawn");

			WORLD = getString("World").trim();
			X = getDouble("X");
			Y = getDouble("Y");
			Z = getDouble("Z");
			YAW = getDouble("Yaw");
			PITCH = getDouble("Pitch");
		}

		public static boolean configured() {
			return !WORLD.isEmpty();
		}
	}
}
