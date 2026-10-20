package pl.kingdomcraft.authenticator.paper.settings;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import pl.kingdomcraft.authenticator.common.config.ConfigException;
import pl.kingdomcraft.authenticator.common.settings.BackendSettings;

/**
 * Reads the part of settings.yml that the proxy shares the format of (node, database, bridge, admin command settings) with the same code
 * the proxy uses. The rest of the file is Foundation's, read by {@link Settings}. A class of its own so that it can be used (and tested)
 * without starting the plugin.
 */
public final class SettingsFile {

	private SettingsFile() {
	}

	/** @throws ConfigException with a message an admin can act on */
	public static BackendSettings load(Path file) throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			final Object root = new Yaml().load(reader);

			if (!(root instanceof Map<?, ?> map))
				throw new ConfigException(file.getFileName() + " is empty. Delete it to get the default back, then restart.");

			return BackendSettings.parse(map, file.toAbsolutePath().getParent());
		}
	}
}
