package pl.kingdomcraft.authenticator.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.settings.ProxySettings;

/** Reads config.yml. The YAML is turned into plain maps and lists and handed to {@link ProxySettings}, which is where the rules are (and where they are tested). */
public final class ConfigLoader {

	private static final String FILE = "config.yml";

	private ConfigLoader() {
	}

	/** @throws ConfigException with a message an admin can act on */
	public static ProxySettings load(Path file) {
		final ConfigurationNode root;

		try {
			root = YamlConfigurationLoader.builder().path(file).build().load();

		} catch (final ConfigurateException ex) {
			throw new ConfigException(FILE + " is not valid YAML: " + ex.getMessage(), ex);
		}

		final Object raw = root.raw();

		if (!(raw instanceof Map<?, ?> map))
			throw new ConfigException(FILE + " is empty. Delete it to get the default back, then restart.");

		return ProxySettings.parse(map, file.toAbsolutePath().getParent());
	}

	/** Copies a bundled resource into the data folder if the file does not exist yet, so admins get the commented default. */
	public static void ensureDefault(Path dataDirectory, String name) throws IOException {
		Files.createDirectories(dataDirectory);

		final Path target = dataDirectory.resolve(name);

		if (Files.exists(target))
			return;

		try (InputStream stream = ConfigLoader.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null)
				throw new IOException("The default " + name + " is missing from the plugin jar");

			Files.copy(stream, target);
		}
	}
}
