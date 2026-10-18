package pl.kingdomcraft.authenticator.velocity.command;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import pl.kingdomcraft.authenticator.common.migrate.MineLoginImporter;
import pl.kingdomcraft.authenticator.common.proxy.ProxyCore;

/**
 * {@code /authadmin}: what only the proxy can do. Account administration (setting passwords, logging players in or out) is done on a
 * backend server, where the admins are; here are the status and the import of mineLogin accounts.
 */
public final class AdminCommand implements SimpleCommand {

	public static final String PERMISSION = "authenticator.admin";

	private static final int EXAMPLES_SHOWN = 10;

	private final ProxyCore core;
	private final Consumer<Runnable> async;
	private final AtomicBoolean importing = new AtomicBoolean();

	/** @param async runs a task off the command thread */
	public AdminCommand(ProxyCore core, Consumer<Runnable> async) {
		this.core = core;
		this.async = async;
	}

	@Override
	public void execute(Invocation invocation) {
		final CommandSource source = invocation.source();
		final String[] args = invocation.arguments();

		if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
			for (final String line : this.core.status())
				source.sendMessage(Component.text(line, NamedTextColor.GRAY));

			return;
		}

		if (args.length >= 2 && args.length <= 3 && args[0].equalsIgnoreCase("migrate") && args[1].equalsIgnoreCase("minelogin")) {
			final boolean dryRun = args.length == 3 && args[2].equalsIgnoreCase("dry-run");

			if (args.length == 3 && !dryRun) {
				this.usage(source);

				return;
			}

			this.migrate(source, dryRun);

			return;
		}

		this.usage(source);
	}

	private void usage(CommandSource source) {
		source.sendMessage(Component.text("Usage: /authadmin status | /authadmin migrate minelogin [dry-run]", NamedTextColor.YELLOW));
	}

	private void migrate(CommandSource source, boolean dryRun) {
		if (!this.importing.compareAndSet(false, true)) {
			source.sendMessage(Component.text("An import is already running.", NamedTextColor.RED));

			return;
		}

		source.sendMessage(Component.text(dryRun ? "Checking the mineLogin accounts (nothing is written)..." : "Importing the mineLogin accounts (they are only read, never changed)...", NamedTextColor.GRAY));

		final String actor = source instanceof Player player ? player.getUsername() : "CONSOLE";

		this.async.accept(() -> {
			try {
				final MineLoginImporter.Report report = this.core.importer().run(dryRun, actor);

				source.sendMessage(Component.text(MineLoginImporter.describe(report), NamedTextColor.GREEN));

				final List<String> examples = report.examples();

				for (final String example : examples.subList(0, Math.min(EXAMPLES_SHOWN, examples.size())))
					source.sendMessage(Component.text("  skipped " + example, NamedTextColor.YELLOW));

				if (examples.size() > EXAMPLES_SHOWN)
					source.sendMessage(Component.text("  ... and " + (examples.size() - EXAMPLES_SHOWN) + " more, see the log", NamedTextColor.YELLOW));

			} catch (final MineLoginImporter.ImportException ex) {
				source.sendMessage(Component.text("Import failed: " + ex.getMessage(), NamedTextColor.RED));

			} catch (final SQLException | RuntimeException ex) {
				source.sendMessage(Component.text("Import failed: " + ex.getMessage(), NamedTextColor.RED));

			} finally {
				this.importing.set(false);
			}
		});
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		return invocation.source().hasPermission(PERMISSION);
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		final String[] args = invocation.arguments();

		return switch (args.length) {
			case 0, 1 -> List.of("status", "migrate");
			case 2 -> args[0].toLowerCase(Locale.ROOT).equals("migrate") ? List.of("minelogin") : List.of();
			case 3 -> args[0].toLowerCase(Locale.ROOT).equals("migrate") ? List.of("dry-run") : List.of();
			default -> List.of();
		};
	}
}
