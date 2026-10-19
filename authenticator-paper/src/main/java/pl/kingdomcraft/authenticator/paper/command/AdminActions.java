package pl.kingdomcraft.authenticator.paper.command;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import pl.kingdomcraft.authenticator.common.admin.AdminService;
import pl.kingdomcraft.authenticator.common.auth.Account;
import pl.kingdomcraft.authenticator.common.gate.BackendCore;
import pl.kingdomcraft.authenticator.common.message.Messages;
import pl.kingdomcraft.authenticator.common.migrate.MineLoginImporter;
import pl.kingdomcraft.authenticator.paper.command.AdminSubCommand.Context;
import pl.kingdomcraft.authenticator.paper.settings.Localization;
import pl.kingdomcraft.authenticator.paper.settings.Msg;

/**
 * What each {@code /auth} subcommand does, on a background thread. The rules are in {@link AdminService}; this turns its answers into
 * messages and tells the proxy of a change so that it acts at once (it would notice from the database within seconds anyway).
 */
final class AdminActions {

	private static final int EXAMPLES_SHOWN = 10;
	private static final AtomicBoolean IMPORTING = new AtomicBoolean();

	private AdminActions() {
	}

	static void info(BackendCore core, Context context) throws Exception {
		final var found = core.admin().info(context.player());

		if (found.isEmpty()) {
			context.tell(Msg.fill(Localization.Admin.NO_SUCH_ACCOUNT, "player", context.player()));

			return;
		}

		final Account account = found.get().account();

		context.tell(Msg.fill(Localization.Admin.INFO_HEADER, "player", account.username()));
		line(context, "UUID", account.uuid().toString());
		line(context, "Type", account.premium() ? "original (premium), Mojang UUID " + account.premiumUuid() : "password" + (account.hashAlgo() == null ? " (none set)" : " (" + account.hashAlgo() + ")"));
		line(context, "Registered", time(account.registeredAt()) + (account.registeredIp() == null ? "" : " from " + account.registeredIp().getHostAddress()));
		line(context, "Last login", account.lastLoginAt() == null ? "never" : time(account.lastLoginAt()) + (account.lastLoginIp() == null ? "" : " from " + account.lastLoginIp().getHostAddress()));
		line(context, "On the network", found.get().presence() == null ? "no" : found.get().presence().state() + (found.get().presence().method() == null ? "" : " (" + found.get().presence().method() + ")"));
	}

	private static void line(Context context, String key, String value) {
		context.tell(Msg.fill(Localization.Admin.INFO_LINE, "key", key, "value", value));
	}

	private static String time(Instant instant) {
		return instant == null ? "unknown" : Messages.formatTime(instant.toString());
	}

	static void unregister(BackendCore core, Context context) {
		report(core, context, core.admin().unregister(context.player(), context.actor()), Localization.Admin.UNREGISTERED);
	}

	static void changePassword(BackendCore core, Context context) {
		report(core, context, core.admin().changePassword(context.player(), context.args()[1], context.actor()), Localization.Admin.PASSWORD_CHANGED);
	}

	static void forceLogin(BackendCore core, Context context) {
		report(core, context, core.admin().forceLogin(context.player(), context.actor()), Localization.Admin.FORCE_LOGIN_DONE);
	}

	static void forceLogout(BackendCore core, Context context) {
		report(core, context, core.admin().forceLogout(context.player(), context.actor()), Localization.Admin.FORCE_LOGOUT_DONE);
	}

	static void setPremium(BackendCore core, Context context) {
		report(core, context, core.admin().setPremium(context.player(), context.actor()), Localization.Admin.SET_PREMIUM_DONE);
	}

	static void setCracked(BackendCore core, Context context) {
		report(core, context, core.admin().setCracked(context.player(), context.args()[1], context.actor()), Localization.Admin.SET_CRACKED_DONE);
	}

	/** The answer of an admin action as a message; after a success, the proxy is told. */
	private static void report(BackendCore core, Context context, AdminService.Result result, String success) {
		final String player = context.player();

		if (result.ok()) {
			context.tell(Msg.fill(success, "player", player));
			context.tell(core.notifyProxy(result.player(), result.change()) ? Localization.Admin.PROXY_TOLD : Localization.Admin.PROXY_NOT_TOLD);

			return;
		}

		context.tell(Msg.fill(failure(result), "player", player, "min", minimum(result.detail())));
	}

	static String failure(AdminService.Result result) {
		return switch (result.outcome()) {
			case NO_SUCH_ACCOUNT -> Localization.Admin.NO_SUCH_ACCOUNT;
			case PREMIUM_ACCOUNT -> Localization.Admin.PREMIUM_ACCOUNT;
			case NOT_PREMIUM_ACCOUNT -> Localization.Admin.NOT_PREMIUM_ACCOUNT;
			case NOT_ONLINE -> Localization.Admin.NOT_ONLINE;
			case ALREADY_LOGGED_IN -> Localization.Admin.ALREADY_LOGGED_IN;
			case NOT_LOGGED_IN -> Localization.Admin.NOT_LOGGED_IN;
			case LOOKUP_FAILED -> Localization.Admin.LOOKUP_FAILED;
			case NOT_ORIGINAL_NAME -> Localization.Admin.NOT_ORIGINAL_NAME;
			case CONFLICT -> Localization.Admin.CONFLICT;
			case WEAK_PASSWORD -> weak(result.detail());
			case OK, ERROR -> Localization.Admin.ERROR;
		};
	}

	private static String weak(String detail) {
		final String violation = detail.contains(":") ? detail.substring(0, detail.indexOf(':')) : detail;

		return switch (violation) {
			case "TOO_SHORT" -> Localization.Admin.WEAK_TOO_SHORT;
			case "TOO_LONG" -> Localization.Admin.WEAK_TOO_LONG;
			case "WHITESPACE" -> Localization.Admin.WEAK_WHITESPACE;
			case "SAME_AS_NAME" -> Localization.Admin.WEAK_SAME_AS_NAME;
			default -> Localization.Admin.WEAK_FORBIDDEN;
		};
	}

	private static String minimum(String detail) {
		return detail.contains(":") ? detail.substring(detail.indexOf(':') + 1) : "";
	}

	static void migrate(BackendCore core, Context context) {
		final boolean dryRun = context.args().length >= 2 && context.args()[1].equalsIgnoreCase("dry-run");

		if (!context.args()[0].equalsIgnoreCase("minelogin") || context.args().length > 2 || context.args().length == 2 && !dryRun) {
			context.tell("&eUsage: /auth migrate minelogin [dry-run]");

			return;
		}

		if (!IMPORTING.compareAndSet(false, true)) {
			context.tell(Localization.Admin.MIGRATE_BUSY);

			return;
		}

		try {
			context.tell(Localization.Admin.MIGRATE_STARTED);

			final MineLoginImporter.Report report = core.importer().run(dryRun, context.actor());

			context.tell("&a" + MineLoginImporter.describe(report));

			final List<String> examples = report.examples();

			for (final String example : examples.subList(0, Math.min(EXAMPLES_SHOWN, examples.size())))
				context.tell(Msg.fill(Localization.Admin.MIGRATE_SKIPPED, "example", example));

		} catch (final MineLoginImporter.ImportException ex) {
			context.tell(Msg.fill(Localization.Admin.MIGRATE_FAILED, "error", String.valueOf(ex.getMessage())));

		} catch (final Exception ex) {
			context.tell(Msg.fill(Localization.Admin.MIGRATE_FAILED, "error", String.valueOf(ex.getMessage())));

		} finally {
			IMPORTING.set(false);
		}
	}

	static void status(BackendCore core, Context context) {
		for (final String line : core.status())
			context.tell(Msg.fill(Localization.Admin.STATUS_LINE, "line", line));
	}
}
