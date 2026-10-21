package pl.kingdomcraft.authenticator.velocity;

import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import pl.kingdomcraft.authenticator.common.message.Messages;

/** Turns a message key and its values into text for a player. Values are inserted as plain text, so a name can never inject formatting. */
public final class Texts {

	private final Messages messages;
	private final MiniMessage miniMessage = MiniMessage.miniMessage();

	public Texts(Messages messages) {
		this.messages = messages;
	}

	public Component render(String key) {
		return this.render(key, Map.of());
	}

	public Component render(String key, Map<String, String> values) {
		final TagResolver[] resolvers = values.entrySet().stream().map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue())).toArray(TagResolver[]::new);

		return this.miniMessage.deserialize(this.messages.get(key), resolvers);
	}
}
