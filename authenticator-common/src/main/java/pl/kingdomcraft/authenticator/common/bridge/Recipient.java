package pl.kingdomcraft.authenticator.common.bridge;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a message is for, described through a player, because that is how PluginMessaging reaches anyone: a message travels over one
 * player's connection. Redis ignores it and publishes to everyone listening.
 */
public record Recipient(Kind kind, UUID player) {

	public enum Kind {

		/** The backend server this player is currently on. */
		SERVER_OF,

		/** The proxy, over this player's connection. */
		PROXY_VIA
	}

	public Recipient {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(player, "player");
	}

	public static Recipient serverOf(UUID player) {
		return new Recipient(Kind.SERVER_OF, player);
	}

	public static Recipient proxyVia(UUID player) {
		return new Recipient(Kind.PROXY_VIA, player);
	}
}
