package pl.kingdomcraft.authenticator.common.premium;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The public lookup services, over HTTPS. Each differs only in its URL and in how it words an answer. */
public final class HttpPremiumApi implements PremiumApi {

	public static final String MOJANG_URL = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
	public static final String ASHCON_URL = "https://api.ashcon.app/mojang/v2/user/";
	public static final String MINETOOLS_URL = "https://api.minetools.eu/uuid/";

	/** No lookup service answers with anything near this much; a bigger body is not one of them. */
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final Pattern UUID_32 = Pattern.compile("[0-9a-fA-F]{32}");

	/** Turns the status and body of an answer into a verdict about the requested name. */
	@FunctionalInterface
	private interface Interpreter extends BiFunction<Integer, JsonObject, Answer> {
	}

	private final String name;
	private final String baseUrl;
	private final HttpClient client;
	private final Duration timeout;
	private final Interpreter interpreter;

	private HttpPremiumApi(String name, String baseUrl, HttpClient client, Duration timeout, Interpreter interpreter) {
		this.name = name;
		this.baseUrl = baseUrl;
		this.client = client;
		this.timeout = timeout;
		this.interpreter = interpreter;
	}

	/** An HTTP client for the lookups: bounded time, and it never follows a redirect to somewhere else. */
	public static HttpClient newClient(Duration timeout) {
		return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
	}

	public static HttpPremiumApi mojang(HttpClient client, Duration timeout, String baseUrl) {
		return new HttpPremiumApi("mojang", baseUrl, client, timeout, (status, body) -> switch (status) {
			case 200 -> fromProfile(body, "id", "name");
			case 204, 404 -> Answer.notFound();
			default -> Answer.unavailable("HTTP " + status);
		});
	}

	public static HttpPremiumApi ashcon(HttpClient client, Duration timeout, String baseUrl) {
		return new HttpPremiumApi("ashcon", baseUrl, client, timeout, (status, body) -> switch (status) {
			case 200 -> fromProfile(body, "uuid", "username");
			case 404 -> Answer.notFound();
			default -> Answer.unavailable("HTTP " + status);
		});
	}

	public static HttpPremiumApi minetools(HttpClient client, Duration timeout, String baseUrl) {
		return new HttpPremiumApi("minetools", baseUrl, client, timeout, (status, body) -> {
			if (status != 200)
				return Answer.unavailable("HTTP " + status);

			final String result = text(body, "status");

			if ("OK".equals(result))
				return fromProfile(body, "id", "name");

			final String message = text(body, "message");

			return message != null && message.toLowerCase(Locale.ROOT).contains("find any profile") ? Answer.notFound() : Answer.unavailable("status " + result);
		});
	}

	/** The default services in the order given, e.g. {@code [mojang, ashcon, minetools]}; unknown names are rejected. */
	public static List<PremiumApi> create(List<String> names, HttpClient client, Duration timeout) {
		return names.stream().map(entry -> switch (entry.trim().toLowerCase(Locale.ROOT)) {
			case "mojang" -> (PremiumApi) mojang(client, timeout, MOJANG_URL);
			case "ashcon" -> ashcon(client, timeout, ASHCON_URL);
			case "minetools" -> minetools(client, timeout, MINETOOLS_URL);
			default -> throw new IllegalArgumentException("unknown premium lookup service '" + entry + "', expected mojang, ashcon or minetools");
		}).toList();
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public Answer lookup(String username) {
		if (!NAME.matcher(username).matches())
			return Answer.notFound(); // not a name Mojang can have, and never something to put into a URL

		final HttpRequest request = HttpRequest.newBuilder(URI.create(this.baseUrl + username)).timeout(this.timeout).header("Accept", "application/json").header("User-Agent", "Authenticator").GET()
				.build();

		try {
			final HttpResponse<InputStream> response = this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());

			try (InputStream stream = response.body()) {
				final byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);

				if (bytes.length > MAX_BODY_BYTES)
					return Answer.unavailable("response too large");

				return this.interpret(username, response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
			}

		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();

			return Answer.unavailable("interrupted");

		} catch (final IOException | RuntimeException ex) {
			return Answer.unavailable(ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
		}
	}

	private Answer interpret(String username, int status, String text) {
		JsonObject body = null;

		try {
			final JsonElement parsed = JsonParser.parseString(text);

			if (parsed.isJsonObject())
				body = parsed.getAsJsonObject();

		} catch (final RuntimeException ex) {
			// no usable body; the status alone may still say enough
		}

		final Answer answer;

		try {
			answer = this.interpreter.apply(status, body == null ? new JsonObject() : body);

		} catch (final RuntimeException ex) {
			return Answer.unavailable("unreadable answer");
		}

		// A service may answer for a name this account used to have; only the current name counts.
		if (answer.kind() == Kind.FOUND && body != null) {
			final String current = text(body, "name") != null ? text(body, "name") : text(body, "username");

			if (current != null && !current.equalsIgnoreCase(username))
				return Answer.notFound();
		}

		return answer;
	}

	private static Answer fromProfile(JsonObject body, String idKey, String nameKey) {
		final String id = text(body, idKey);

		if (id == null)
			return Answer.unavailable("no " + idKey + " in the answer");

		final UUID uuid = parseUuid(id);

		return uuid == null ? Answer.unavailable("malformed " + idKey) : Answer.found(uuid);
	}

	private static String text(JsonObject body, String key) {
		final JsonElement element = body.get(key);

		return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
	}

	/** @return the UUID, written with or without dashes, or null if the text is not one */
	public static UUID parseUuid(String text) {
		try {
			if (UUID_32.matcher(text).matches())
				return UUID.fromString(text.replaceFirst("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})", "$1-$2-$3-$4-$5"));

			return text.length() == 36 ? UUID.fromString(text) : null;

		} catch (final IllegalArgumentException ex) {
			return null;
		}
	}
}
