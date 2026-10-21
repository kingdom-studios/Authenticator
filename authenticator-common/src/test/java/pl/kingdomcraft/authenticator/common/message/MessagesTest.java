package pl.kingdomcraft.authenticator.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.auth.AuthService.OpenFailure;
import pl.kingdomcraft.authenticator.common.auth.AuthService.Outcome;
import pl.kingdomcraft.authenticator.common.auth.PasswordPolicy.Violation;
import pl.kingdomcraft.authenticator.common.auth.PreLoginDecider.Reason;
import pl.kingdomcraft.authenticator.common.config.ConfigException;

class MessagesTest {

	private static final Pattern PLACEHOLDER = Pattern.compile("<(attempts|until|name|min|limit)>");
	private static final Pattern ANY_TAG = Pattern.compile("<[^<>]*>");

	private static Set<String> placeholders(String text) {
		final Set<String> found = new TreeSet<>();
		final Matcher matcher = PLACEHOLDER.matcher(text);

		while (matcher.find())
			found.add(matcher.group(1));

		return found;
	}

	@Test
	void everyLanguageHasTheSameKeysAndPlaceholders() {
		final Properties pl = Messages.catalog("pl");
		final Properties en = Messages.catalog("en");

		assertEquals(new TreeSet<>(pl.stringPropertyNames()), new TreeSet<>(en.stringPropertyNames()));

		for (final String key : pl.stringPropertyNames())
			assertEquals(placeholders(pl.getProperty(key)), placeholders(en.getProperty(key)), "placeholders of " + key);
	}

	@Test
	void everyCatalogIsPlainWellFormedText() {
		for (final String language : Messages.LANGUAGES) {
			final Properties catalog = Messages.catalog(language);

			for (final String key : catalog.stringPropertyNames()) {
				final String text = catalog.getProperty(key);

				assertFalse(text.isBlank(), language + " " + key);
				assertFalse(text.indexOf(0xFFFD) >= 0, language + " " + key + " is not valid UTF-8");

				// A lone angle bracket would confuse MiniMessage; every one belongs to a tag.
				final String withoutTags = ANY_TAG.matcher(text).replaceAll("");

				assertFalse(withoutTags.contains("<") || withoutTags.contains(">"), language + " " + key + " has a stray angle bracket: " + text);
			}
		}
	}

	@Test
	void everyThingTheCodeCanSayHasAText() {
		final Messages messages = Messages.load("en", Map.of());

		for (final Outcome outcome : Outcome.values()) {
			final String key = Messages.key(outcome);

			switch (outcome) {
				// Said by the command itself: its own success, kick or confirmation texts.
				case OK, OK_RECONNECT, TOO_MANY_ATTEMPTS, IP_BLOCKED, WEAK_PASSWORD, CONFIRM_REQUIRED -> {
				}
				default -> assertTrue(messages.has(key), "no text for " + outcome + " (" + key + ")");
			}
		}

		for (final Reason reason : Reason.values())
			switch (reason) {
				case TOO_MANY_CONNECTIONS, BAD_NICKNAME, IP_BLOCKED, ALREADY_ONLINE, WRONG_CASE, PREMIUM_LOOKUP_FAILED, DATABASE_ERROR -> assertTrue(messages.has(Messages.key(reason)), "no text for " + reason);
				default -> {
					// These reasons let the player in; there is nothing to tell them.
				}
			}

		for (final OpenFailure failure : OpenFailure.values())
			assertTrue(messages.has(Messages.key(failure)), "no text for " + failure);

		for (final Violation violation : Violation.values())
			assertTrue(messages.has(Messages.weakKey(violation.name())), "no text for " + violation);

		assertEquals("weak.too-short", Messages.weakKey("TOO_SHORT:6"));
	}

	@Test
	void theTextsNeedTheValuesTheCodeFillsIn() {
		final Messages messages = Messages.load("en", Map.of());

		assertTrue(messages.get("outcome.wrong-password").contains("<attempts>"));
		assertTrue(messages.get("outcome.too-many-accounts").contains("<limit>"));
		assertTrue(messages.get("kick.ip-blocked").contains("<until>"));
		assertTrue(messages.get("deny.ip-blocked").contains("<until>"));
		assertTrue(messages.get("deny.wrong-case").contains("<name>"));
		assertTrue(messages.get("weak.too-short").contains("<min>"));
	}

	@Test
	void aConfiguredTextReplacesTheBuiltInOne() {
		final Messages messages = Messages.load("pl", Map.of("login.ok", "<green>Hej!"));

		assertEquals("<green>Hej!", messages.get("login.ok"));
		assertEquals(Messages.catalog("pl").getProperty("register.ok"), messages.get("register.ok"));
	}

	@Test
	void theLanguageChoosesTheBuiltInTexts() {
		assertTrue(Messages.load("pl", Map.of()).get("login.ok").contains("Zalogowano"));
		assertTrue(Messages.load("EN", Map.of()).get("login.ok").contains("Logged in"));
	}

	@Test
	void polishDiacriticsSurviveTheRoundTrip() {
		assertTrue(Messages.catalog("pl").getProperty("prompt.login").contains("hasło"));
		assertTrue(Messages.catalog("pl").getProperty("prompt.login").contains("się"));
	}

	@Test
	void anUnknownLanguageOrKeyIsRefusedByName() {
		assertTrue(assertThrows(ConfigException.class, () -> Messages.load("de", Map.of())).getMessage().contains("'de'"));

		final ConfigException ex = assertThrows(ConfigException.class, () -> Messages.load("en", Map.of("login-ok", "x", "login.ok", "y", "bogus", "z")));

		assertTrue(ex.getMessage().contains("login-ok") && ex.getMessage().contains("bogus"), ex.getMessage());
		assertFalse(ex.getMessage().contains("[login.ok"), "a known key is not a mistake");
	}

	@Test
	void aMissingKeyStillGivesReadableText() {
		assertEquals("<red>no.such.key", Messages.load("en", Map.of()).get("no.such.key"));
	}

	@Test
	void timesAreShownInAReadableFormAndOddInputIsLeftAlone() {
		assertEquals("2026-10-18 14:30 UTC", Messages.formatTime("2026-10-18T14:30:12.345Z"));
		assertEquals("soon", Messages.formatTime("soon"));
	}

	@Test
	void aRefusalGetsTheValuesItsTextNeeds() {
		assertEquals(Map.of("name", "Notch"), Messages.denyValues(Reason.WRONG_CASE, "Notch"));
		assertEquals(Map.of("until", "2026-10-18 14:30 UTC"), Messages.denyValues(Reason.IP_BLOCKED, "2026-10-18T14:30:00Z"));
		assertEquals(Map.of(), Messages.denyValues(Reason.BAD_NICKNAME, ""));

		final Messages messages = Messages.load("en", Map.of());

		for (final Reason reason : Reason.values())
			if (messages.has(Messages.key(reason)))
				for (final String name : Messages.denyValues(reason, "2026-10-18T14:30:00Z").keySet())
					assertTrue(messages.get(Messages.key(reason)).contains("<" + name + ">"), reason + " does not use <" + name + ">");
	}
}
