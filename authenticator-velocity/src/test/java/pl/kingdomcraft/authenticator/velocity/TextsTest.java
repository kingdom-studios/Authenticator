package pl.kingdomcraft.authenticator.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import pl.kingdomcraft.authenticator.common.message.Messages;

class TextsTest {

	private static String plain(Component component) {
		return PlainTextComponentSerializer.plainText().serialize(component);
	}

	@Test
	void everyBuiltInMessageOfBothLanguagesRendersWithItsFormatting() {
		for (final String language : Messages.LANGUAGES) {
			final Messages messages = Messages.load(language, Map.of());
			final Texts texts = new Texts(messages);

			for (final String key : messages.keys()) {
				final Component component = texts.render(key, Map.of("attempts", "2", "until", "later", "name", "Steve", "min", "6", "limit", "3"));
				final String text = plain(component);

				assertFalse(text.isBlank(), language + " " + key);
				assertFalse(text.contains("<") || text.contains(">"), language + " " + key + " has a tag that was not understood: " + text);
				assertFalse(text.matches(".*\\b(attempts|until|min|limit)\\b>.*"), language + " " + key);
			}
		}
	}

	@Test
	void valuesAreInsertedAsPlainTextSoNoNameCanInjectFormatting() {
		final Texts texts = new Texts(Messages.load("en", Map.of()));

		final Component component = texts.render("deny.wrong-case", Map.of("name", "<red>Evil</red>"));

		assertTrue(plain(component).contains("<red>Evil</red>"), "shown literally: " + plain(component));
		assertFalse(plain(component).contains("Evil</red>") && component.children().stream().anyMatch(child -> NamedTextColor.RED.equals(child.color()) && plain(child).equals("Evil")));
	}

	@Test
	void aConfiguredTextIsUsed() {
		final Texts texts = new Texts(Messages.load("en", Map.of("login.ok", "<green>Welcome back!")));

		assertEquals("Welcome back!", plain(texts.render("login.ok")));
	}

	@Test
	void theColourOfAMessageIsKept() {
		final Component component = new Texts(Messages.load("en", Map.of())).render("login.ok");

		assertEquals(NamedTextColor.GREEN, component.color() != null ? component.color() : component.children().get(0).color());
	}
}
