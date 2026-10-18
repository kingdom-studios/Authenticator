package pl.kingdomcraft.authenticator.velocity.command;

import java.util.List;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import net.kyori.adventure.text.Component;

import pl.kingdomcraft.authenticator.common.auth.PlayerSession;
import pl.kingdomcraft.authenticator.common.message.AuthCommands.Command;
import pl.kingdomcraft.authenticator.common.proxy.ProxyCore;
import pl.kingdomcraft.authenticator.velocity.Connections;
import pl.kingdomcraft.authenticator.velocity.Texts;

/**
 * One of the player commands ({@code /login}, {@code /register}...). It lives on the proxy, so a password typed in it never reaches a backend
 * server. All the rules are in {@code AuthCommands}; this only passes the arguments in and the answer out.
 *
 * <p>Nothing here logs the arguments: they are passwords.
 */
public final class PlayerCommand implements SimpleCommand {

	private final Command command;
	private final ProxyCore core;
	private final Connections connections;
	private final Texts texts;

	public PlayerCommand(Command command, ProxyCore core, Connections connections, Texts texts) {
		this.command = command;
		this.core = core;
		this.connections = connections;
		this.texts = texts;
	}

	@Override
	public void execute(Invocation invocation) {
		final CommandSource source = invocation.source();

		if (!(source instanceof Player player)) {
			source.sendMessage(Component.text("This command is for players."));

			return;
		}

		final PlayerSession session = this.connections.session(player);

		if (session == null) {
			player.sendMessage(this.texts.render("open.unverified-profile"));

			return;
		}

		this.core.commands().run(session, this.command, List.of(invocation.arguments())).thenAccept(response -> {
			final Component text = this.texts.render(response.key(), response.values());

			if (response.kick())
				player.disconnect(text);
			else
				player.sendMessage(text);
		});
	}

	/** No completions: an argument is a password. */
	@Override
	public List<String> suggest(Invocation invocation) {
		return List.of();
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		return true;
	}
}
