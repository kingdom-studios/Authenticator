package pl.kingdomcraft.authenticator.common.message;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import pl.kingdomcraft.authenticator.common.auth.AuthService;
import pl.kingdomcraft.authenticator.common.auth.AuthService.Outcome;
import pl.kingdomcraft.authenticator.common.auth.AuthService.Result;
import pl.kingdomcraft.authenticator.common.auth.PlayerSession;

/**
 * The player commands, platform-free: arguments in, a message key and its values out. The platform registers the commands, passes the
 * arguments and shows the answer (kicking the player if asked to); it contains no rules of its own.
 */
public final class AuthCommands {

	public enum Command {
		LOGIN,
		REGISTER,
		CHANGEPASSWORD,
		UNREGISTER,
		PREMIUM,
		CRACKED;

		public String commandName() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static Optional<Command> byName(String name) {
			for (final Command command : values())
				if (command.commandName().equals(name.toLowerCase(Locale.ROOT)))
					return Optional.of(command);

			return Optional.empty();
		}
	}

	/**
	 * What to tell the player.
	 *
	 * @param key    the message
	 * @param values placeholder values for it, by name
	 * @param kick   whether the message is the reason the player is disconnected
	 * @param ok     whether the command did what was asked
	 */
	public record Response(String key, Map<String, String> values, boolean kick, boolean ok) {

		static Response message(String key) {
			return new Response(key, Map.of(), false, false);
		}

		static Response message(String key, String name, String value) {
			return new Response(key, Map.of(name, value), false, false);
		}
	}

	private final AuthService auth;

	public AuthCommands(AuthService auth) {
		this.auth = auth;
	}

	/** Never completes exceptionally; the future may complete on a database or hashing thread. */
	public CompletableFuture<Response> run(PlayerSession session, Command command, List<String> args) {
		final CompletableFuture<Result> result;

		switch (command) {
			case LOGIN -> {
				// Imported mineLogin passwords may contain spaces; the proxy has split the line at them, so put them back.
				if (args.isEmpty())
					return done(Response.message("usage.login"));

				result = this.auth.login(session, String.join(" ", args));
			}

			case REGISTER -> {
				if (args.size() != 2)
					return done(Response.message("usage.register"));

				result = this.auth.register(session, args.get(0), args.get(1));
			}

			case CHANGEPASSWORD -> {
				if (args.size() != 2)
					return done(Response.message("usage.changepassword"));

				result = this.auth.changePassword(session, args.get(0), args.get(1));
			}

			case UNREGISTER -> {
				if (args.size() != 1)
					return done(Response.message("usage.unregister"));

				result = this.auth.unregister(session, args.get(0));
			}

			case PREMIUM -> {
				if (!args.isEmpty())
					return done(Response.message("usage.premium"));

				result = this.auth.premium(session);
			}

			case CRACKED -> {
				if (args.size() != 2)
					return done(Response.message("usage.cracked"));

				result = this.auth.cracked(session, args.get(0), args.get(1));
			}

			default -> throw new IllegalStateException(String.valueOf(command));
		}

		return result.handle((value, ex) -> ex == null ? respond(command, value) : Response.message(Messages.key(Outcome.ERROR)));
	}

	/** Turns what a command achieved into the text for it. */
	public static Response respond(Command command, Result result) {
		return switch (result.outcome()) {
			case OK -> new Response(switch (command) {
				case LOGIN -> "login.ok";
				case REGISTER -> "register.ok";
				case CHANGEPASSWORD -> "changepassword.ok";
				default -> "outcome.error"; // no other command ends with OK
			}, Map.of(), false, true);

			case OK_RECONNECT -> new Response(switch (command) {
				case PREMIUM -> "kick.reconnect.premium";
				case CRACKED -> "kick.reconnect.cracked";
				case UNREGISTER -> "kick.unregistered";
				default -> "outcome.error";
			}, Map.of(), true, true);

			case WRONG_PASSWORD -> Response.message(Messages.key(Outcome.WRONG_PASSWORD), "attempts", result.detail());
			case TOO_MANY_ATTEMPTS -> new Response("kick.too-many-attempts", Map.of(), true, false);
			case IP_BLOCKED -> new Response("kick.ip-blocked", Map.of("until", Messages.formatTime(result.detail())), true, false);
			case WEAK_PASSWORD -> weak(result.detail());
			case TOO_MANY_ACCOUNTS -> Response.message(Messages.key(Outcome.TOO_MANY_ACCOUNTS), "limit", result.detail());
			case CONFIRM_REQUIRED -> Response.message(command == Command.PREMIUM || command == Command.CRACKED ? "outcome.confirm-required." + command.commandName() : Messages.key(Outcome.ERROR));

			case NOT_LOGGED_IN, ALREADY_LOGGED_IN, ALREADY_REGISTERED, NOT_REGISTERED, PASSWORD_MISMATCH, NAME_TAKEN, PREMIUM_ACCOUNT, NOT_PREMIUM_ACCOUNT, NOT_ORIGINAL_NAME, LOOKUP_FAILED, BUSY, CONFLICT,
					ERROR -> Response.message(Messages.key(result.outcome()));
		};
	}

	private static Response weak(String detail) {
		final int colon = detail.indexOf(':');

		return colon < 0 ? Response.message(Messages.weakKey(detail)) : Response.message(Messages.weakKey(detail), "min", detail.substring(colon + 1));
	}

	private static CompletableFuture<Response> done(Response response) {
		return CompletableFuture.completedFuture(response);
	}
}
