package pl.kingdomcraft.authenticator.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigSectionTest {

	private static ConfigSection section(Object... pairs) {
		final Map<String, Object> map = new LinkedHashMap<>();

		for (int i = 0; i < pairs.length; i += 2)
			map.put((String) pairs[i], pairs[i + 1]);

		return ConfigSection.of("root", map);
	}

	@Test
	void keysIgnoreCaseAndTreatUnderscoreLikeHyphen() {
		final ConfigSection section = section("Pool_Size", 6, "connection-timeout-ms", 100);

		assertEquals(6, section.integer("pool-size", 0));
		assertEquals(6, section.integer("POOL_SIZE", 0));
		assertEquals(100, section.integer("Connection_Timeout_Ms", 0));
	}

	@Test
	void sameKeyWrittenTwoWaysIsRejected() {
		final ConfigException ex = assertThrows(ConfigException.class, () -> section("pool-size", 1, "Pool_Size", 2));

		assertTrue(ex.getMessage().contains("root.pool-size"), ex.getMessage());
	}

	@Test
	void errorsNameTheFullPath() {
		final ConfigSection nested = section("database", Map.of("port", "abc")).requireSection("database");

		final ConfigException ex = assertThrows(ConfigException.class, () -> nested.integer("port", 3306));

		assertTrue(ex.getMessage().startsWith("root.database.port:"), ex.getMessage());
		assertTrue(ex.getMessage().contains("abc"), ex.getMessage());
	}

	@Test
	void integerAcceptsNumbersAndNumericText() {
		assertEquals(5, section("a", 5).integer("a", 0));
		assertEquals(5, section("a", 5L).integer("a", 0));
		assertEquals(5, section("a", " 5 ").integer("a", 0));
		assertEquals(7, section().integer("a", 7));
	}

	@Test
	void integerRejectsFractionsOverflowAndBooleans() {
		assertThrows(ConfigException.class, () -> section("a", 1.5).integer("a", 0));
		assertThrows(ConfigException.class, () -> section("a", Long.MAX_VALUE).integer("a", 0));
		assertThrows(ConfigException.class, () -> section("a", true).integer("a", 0));
		assertThrows(ConfigException.class, () -> section("a", List.of(1)).integer("a", 0));
	}

	@Test
	void booleanAcceptsBooleansAndTrueFalseText() {
		assertTrue(section("a", true).bool("a", false));
		assertTrue(section("a", "TRUE").bool("a", false));
		assertFalse(section("a", "false").bool("a", true));
		assertTrue(section().bool("a", true));
		assertThrows(ConfigException.class, () -> section("a", "yes").bool("a", false));
		assertThrows(ConfigException.class, () -> section("a", 1).bool("a", false));
	}

	@Test
	void stringListAcceptsListsSingleValuesAndNothing() {
		assertEquals(List.of("a", "b"), section("l", List.of("a", "b")).stringList("l"));
		assertEquals(List.of("a"), section("l", "a").stringList("l"));
		assertEquals(List.of(), section().stringList("l"));
		assertEquals(List.of(), section("l", List.of()).stringList("l"));
		assertThrows(ConfigException.class, () -> section("l", Map.of("a", 1)).stringList("l"));
		assertThrows(ConfigException.class, () -> section("l", List.of(List.of("x"))).stringList("l"));
	}

	@Test
	void stringMapKeepsKeysAsWritten() {
		final Map<String, String> map = section("p", Map.of("serverSslCert", "/x.pem", "count", 3)).stringMap("p");

		assertEquals("/x.pem", map.get("serverSslCert"));
		assertEquals("3", map.get("count"));
		assertEquals(Map.of(), section().stringMap("p"));
	}

	@Test
	void requiredValuesMustBePresentAndNotBlank() {
		assertThrows(ConfigException.class, () -> section().requireString("host"));
		assertThrows(ConfigException.class, () -> section("host", "  ").requireString("host"));
		assertEquals("db", section("host", "db").requireString("host"));
		assertThrows(ConfigException.class, () -> section().requireSection("endpoints"));
	}

	@Test
	void sectionMustBeASection() {
		assertNull(section().section("x"));
		assertThrows(ConfigException.class, () -> section("x", "text").section("x"));
		assertThrows(ConfigException.class, () -> section("x", Map.of()).string("x", null));
	}
}
