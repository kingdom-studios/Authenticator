package pl.kingdomcraft.authenticator.paper.placeholder;

import java.util.Optional;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.model.SimpleExpansion;

import pl.kingdomcraft.authenticator.common.auth.AuthState;
import pl.kingdomcraft.authenticator.common.gate.AccountSnapshots;
import pl.kingdomcraft.authenticator.common.gate.BackendCore;
import pl.kingdomcraft.authenticator.common.gate.Gate;
import pl.kingdomcraft.authenticator.paper.AuthenticatorPaper;
import pl.kingdomcraft.authenticator.paper.settings.Localization;

/**
 * PlaceholderAPI placeholders. Foundation hooks every {@link SimpleExpansion} into PlaceholderAPI under the plugin's name, which is
 * {@code authenticator}:
 * <ul>
 * <li>{@code %authenticator_state%}: {@code authenticated}, {@code pending_login}, {@code pending_register} or {@code unknown};</li>
 * <li>{@code %authenticator_premium%}: whether the account is an original one;</li>
 * <li>{@code %authenticator_registered%}: whether the player has an account.</li>
 * </ul>
 *
 * <p>PlaceholderAPI may call this on the main thread many times a tick, so this only reads memory: the state from the {@link Gate} and the rest
 * from {@link AccountSnapshots}, which is filled in the background. The expansion is a singleton shared by all callers and keeps no state.
 */
@AutoRegister
public final class AuthExpansion extends SimpleExpansion {

	/**
	 * Foundation insists that an auto-registered expansion is a singleton: exactly one private no-args constructor and a private static
	 * final field holding the instance. It finds the instance through this field by reflection, so the field is never read from code.
	 */
	@SuppressWarnings("unused")
	private static final AuthExpansion instance = new AuthExpansion();

	private AuthExpansion() {
	}

	@Override
	protected String onReplace(CommandSender sender, String identifier) {
		if (!Values.isKnown(identifier))
			return NO_REPLACE;

		if (!(sender instanceof Player player))
			return "";

		final BackendCore core = AuthenticatorPaper.core();
		final Optional<AccountSnapshots.Snapshot> snapshot = core == null ? Optional.empty() : core.snapshots().peek(player.getUniqueId());

		return Values.resolve(identifier, AuthenticatorPaper.gate().get(player.getUniqueId()).map(Gate.Entry::state).orElse(null), snapshot.orElse(null));
	}

	/** The values, apart from Foundation and PlaceholderAPI so that they can be tested. */
	static final class Values {

		private Values() {
		}

		static boolean isKnown(String identifier) {
			return identifier.equals("state") || identifier.equals("premium") || identifier.equals("registered");
		}

		/**
		 * @param state    what the gate knows, or null
		 * @param snapshot what the database said last time, or null if it has not been read yet
		 */
		static String resolve(String identifier, AuthState state, AccountSnapshots.Snapshot snapshot) {
			return switch (identifier) {
				case "state" -> state == null ? Localization.Placeholders.STATE_UNKNOWN : switch (state) {
					case AUTHENTICATED -> Localization.Placeholders.STATE_AUTHENTICATED;
					case PENDING_LOGIN -> Localization.Placeholders.STATE_PENDING_LOGIN;
					case PENDING_REGISTER -> Localization.Placeholders.STATE_PENDING_REGISTER;
				};

				case "premium" -> snapshot == null ? Localization.Placeholders.LOADING : snapshot.premium() ? Localization.Placeholders.TRUE_TEXT : Localization.Placeholders.FALSE_TEXT;
				case "registered" -> snapshot == null ? Localization.Placeholders.LOADING : snapshot.registered() ? Localization.Placeholders.TRUE_TEXT : Localization.Placeholders.FALSE_TEXT;
				default -> SimpleExpansion.NO_REPLACE;
			};
		}
	}
}
