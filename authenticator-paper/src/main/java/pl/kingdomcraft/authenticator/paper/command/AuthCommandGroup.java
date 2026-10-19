package pl.kingdomcraft.authenticator.paper.command;

import java.util.List;

import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.command.ReloadCommand;
import org.mineacademy.fo.command.SimpleCommandGroup;

/**
 * The {@code /auth} command group (its labels come from {@code Command_Aliases} in settings.yml; Foundation registers it automatically).
 * It is not called {@code /authadmin} because the proxy has a command of that name, and a command registered on the proxy wins over a
 * backend server's for a player.
 */
@AutoRegister
public final class AuthCommandGroup extends SimpleCommandGroup {

	@Override
	protected void registerSubcommands() {
		this.registerSubcommand(new ReloadCommand(AdminSubCommand.PERMISSION));

		this.registerSubcommand(new AdminSubCommand("info", "<player>", "Show an account.", 1, true, List.of(), AdminActions::info));
		this.registerSubcommand(new AdminSubCommand("unregister", "<player>", "Remove an account.", 1, true, List.of(), (core, context) -> AdminActions.unregister(core, context)));
		this.registerSubcommand(new AdminSubCommand("changepassword", "<player> <password>", "Set a new password.", 2, true, List.of(), (core, context) -> AdminActions.changePassword(core, context)));
		this.registerSubcommand(new AdminSubCommand("forcelogin", "<player>", "Log in a player who is waiting to.", 1, true, List.of(), (core, context) -> AdminActions.forceLogin(core, context)));
		this.registerSubcommand(new AdminSubCommand("forcelogout", "<player>", "Make a logged in player type the password again.", 1, true, List.of(), (core, context) -> AdminActions.forceLogout(core, context)));
		this.registerSubcommand(new AdminSubCommand("setpremium", "<player>", "Turn a password account into an original one.", 1, true, List.of(), (core, context) -> AdminActions.setPremium(core, context)));
		this.registerSubcommand(new AdminSubCommand("setcracked", "<player> <password>", "Turn an original account into a password account.", 2, true, List.of(), (core, context) -> AdminActions.setCracked(core, context)));
		this.registerSubcommand(new AdminSubCommand("migrate", "minelogin [dry-run]", "Import the accounts of mineLogin.", 1, false, List.of("minelogin", "dry-run"), AdminActions::migrate));
		this.registerSubcommand(new AdminSubCommand("status", "", "Show what this server is doing.", 0, false, List.of(), AdminActions::status));
	}

	@Override
	protected String getCredits() {
		return "&7Authenticator - premium detection and password login.";
	}
}
