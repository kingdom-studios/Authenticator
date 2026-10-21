package pl.kingdomcraft.authenticator.paper.settings;

/** Fills {@code {placeholders}} in a message from the localization file. */
public final class Msg {

	private Msg() {
	}

	/**
	 * @param template a message such as {@code "&a{player} is being logged in."}
	 * @param pairs    placeholder names and values, alternating: {@code "player", "Steve"}
	 */
	public static String fill(String template, String... pairs) {
		if (pairs.length % 2 != 0)
			throw new IllegalArgumentException("placeholders come in name and value pairs");

		String text = template;

		for (int i = 0; i < pairs.length; i += 2)
			text = text.replace("{" + pairs[i] + "}", pairs[i + 1]);

		return text;
	}
}
