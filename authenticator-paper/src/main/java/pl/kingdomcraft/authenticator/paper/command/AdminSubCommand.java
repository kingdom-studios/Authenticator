package pl.kingdomcraft.authenticator.paper.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleSubCommand;

import pl.kingdomcraft.authenticator.common.gate.BackendCore;
import pl.kingdomcraft.authenticator.paper.AuthenticatorPaper;
import pl.kingdomcraft.authenticator.paper.settings.Localization;

/**
 * One {@code /auth} subcommand. The flow is the same for all of them:
 * <ol>
 * <li><b>main thread</b>: check the plugin is running and copy out the sender and the arguments (a command object is shared by every
 * invocation, so nothing about one may stay in a field);</li>
 * <li><b>background thread</b>: the work, which reads and writes the database and may hash a password;</li>
 * <li>the answer goes back to the sender on the main thread.</li>
 * </ol>
 */
final class AdminSubCommand extends SimpleSubCommand {

	/** Everything a background job needs about one invocation. */
	record Context(CommandSender sender, String actor, String[] args) {

		/** The player the command is about. */
		String player() {
			return this.args[0];
		}

		void tell(String message) {
			Common.runLater(() -> Common.tell(this.sender, message));
		}
	}

	@FunctionalInterface
	interface Job {
		void run(BackendCore core, Context context) throws Exception;
	}

	static final String PERMISSION = "authenticator.admin";

	private final Job job;
	private final boolean takesPlayer;
	private final List<String> words;

	/**
	 * @param takesPlayer whether the first argument is a player name (completed with the names of those online)
	 * @param words       fixed words to complete at each position after the player: {@code "minelogin"}, ...
	 */
	AdminSubCommand(String label, String usage, String description, int minArguments, boolean takesPlayer, List<String> words, Job job) {
		super(label);

		this.job = job;
		this.takesPlayer = takesPlayer;
		this.words = List.copyOf(words);

		this.setPermission(PERMISSION);
		this.setUsage(usage);
		this.setDescription(description);
		this.setMinArguments(minArguments);
	}

	@Override
	protected void onCommand() {
		final BackendCore core = AuthenticatorPaper.core();

		if (core == null)
			this.returnTell(Localization.Admin.STARTING);

		final CommandSender sender = this.sender;
		final Context context = new Context(sender, sender instanceof Player player ? player.getName() : "CONSOLE", this.args.clone());

		Common.runAsync(() -> {
			try {
				this.job.run(core, context);

			} catch (final Throwable t) {
				Common.error(t, "Failed to run /" + this.getLabel() + " " + this.getSublabel());
				context.tell(Localization.Admin.ERROR);
			}
		});
	}

	@Override
	protected List<String> tabComplete() {
		if (this.takesPlayer && this.args.length == 1)
			return this.completeLastWord(Common.getPlayerNames());

		final int index = this.args.length - (this.takesPlayer ? 2 : 1);

		return index >= 0 && index < this.words.size() ? this.completeLastWord(new ArrayList<>(List.of(this.words.get(index).split("\\|")))) : List.of();
	}
}
