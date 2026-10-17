package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class MigratorTest {

	@Test
	void splitsStatementsAndSkipsComments() {
		final List<String> statements = Migrator.splitStatements("-- header\nCREATE TABLE a (\n  id INT\n);\n\nCREATE TABLE b (id INT);\n");

		assertEquals(2, statements.size());
		assertTrue(statements.get(0).startsWith("CREATE TABLE a"));
		assertFalse(statements.get(0).endsWith(";"));
	}

	@Test
	void bundledMigrationsAreValidAndAscending() {
		final List<Migrator.Migration> migrations = Migrator.loadMigrations();

		assertEquals(List.of(1, 2, 3, 4), migrations.stream().map(Migrator.Migration::version).toList());
		assertEquals("accounts", migrations.get(0).description());
		assertEquals(Migrator.latestVersion(), 4);

		for (final Migrator.Migration migration : migrations)
			assertFalse(Migrator.splitStatements(migration.sql()).isEmpty(), "V" + migration.version() + " has no statements");
	}

	/** A migration file that is not in the list would silently never run. */
	@Test
	void everyMigrationFileIsListed() throws IOException {
		final Set<String> onDisk = new TreeSet<>();

		try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/mariadb"))) {
			files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sql")).forEach(onDisk::add);
		}

		final Set<String> listed = new TreeSet<>();

		for (final String line : Files.readAllLines(Path.of("src/main/resources/db/mariadb/migrations.list")))
			if (!line.isBlank() && !line.startsWith("#"))
				listed.add(line.trim().split("\\s+", 2)[1]);

		assertEquals(onDisk, listed);
	}

	@Test
	void migrationsAvoidInlineCommentsThatTheSplitterCouldMisread() {
		for (final Migrator.Migration migration : Migrator.loadMigrations())
			for (final String line : migration.sql().split("\\R"))
				assertFalse(line.contains("--") && !line.trim().startsWith("--"), "comments must be on their own line: " + line);
	}
}
