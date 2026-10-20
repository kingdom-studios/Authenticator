package pl.kingdomcraft.authenticator.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings an admin's config file up to the layout of the one shipped in the jar without losing a value and without losing the comments of
 * the new file. A YAML library cannot do that (it drops comments), so the shipped file is the template and the old file only supplies
 * values:
 *
 * <ol>
 *   <li>every key of the template that the old file lacks is added, with the comments above it;</li>
 *   <li>every key the old file has keeps the value the admin wrote, written where the template has the key: a section is walked into,
 *       anything else (a scalar, a list, a map such as {@code properties} or {@code messages}) is copied from the old file line by line,
 *       so entries the template knows nothing about survive, and so do the admin's own comments inside them;</li>
 *   <li>keys are matched ignoring case, {@code -} and {@code _}, so {@code Ssl_Mode} and {@code ssl-mode} are the same key; the template's
 *       spelling is written;</li>
 *   <li>a key the template no longer has is not carried over; it is logged, and it is still in the backup.</li>
 * </ol>
 *
 * The file is only touched when it needs it: a key is missing, or the file has no version or an older one than the template. Then the old
 * file is first copied to {@code <name>.bak} (a number is added if a backup is already there). A file that has no keys at all is left to
 * the parser, which says what is wrong with it.
 *
 * <p>The files are read the way the shipped ones are written: block mappings indented with spaces, one key per line. That is also how the
 * plugin writes them, and a file that is not like that (a whole document in flow style, say) has no keys here and is left alone.
 */
public final class ConfigUpdater {

	/** What happened to the file. */
	public enum Status {
		/** There is no file, or it was already complete. */
		UNCHANGED,
		/** The file was rewritten; the old one is in {@link Result#backup()}. */
		UPDATED
	}

	/**
	 * @param added   keys that were not in the old file, as dotted paths (only the topmost of a missing section)
	 * @param dropped keys of the old file that the template does not have, and so were not carried over
	 * @param backup  the copy of the old file, or null when nothing was changed
	 */
	public record Result(Status status, List<String> added, List<String> dropped, Path backup) {
	}

	private static final Pattern KEY_LINE = Pattern.compile("^( *)(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^\\s#'\"\\-?|>&*!%@`\\[\\]{},][^:#]*?|-[^\\s:#][^:#]*?)[ \\t]*:(?:[ \\t]+(.*))?$");

	private ConfigUpdater() {
	}

	/** One key and everything under it. */
	private static final class Node {
		final String key;
		final String norm;
		final int indent;
		/** Index of the key line. */
		final int start;
		/** One past the last line that belongs to the key (trailing blank and comment lines are not part of it). */
		int end;
		/** What follows the colon on the key line, without a comment; empty for a section or a block list. */
		final String inline;
		/** The child keys when this is a section; null for a value. */
		List<Node> children;

		Node(String key, int indent, int start, String inline) {
			this.key = key;
			this.norm = normalize(key);
			this.indent = indent;
			this.start = start;
			this.end = start + 1;
			this.inline = inline;
		}
	}

	private static String normalize(String key) {
		String text = key.trim();

		if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.charAt(text.length() - 1) == text.charAt(0))
			text = text.substring(1, text.length() - 1);

		return text.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
	}

	private static boolean trivia(String line) {
		final String trimmed = line.strip();

		return trimmed.isEmpty() || trimmed.startsWith("#");
	}

	private static int indentOf(String line) {
		int i = 0;

		while (i < line.length() && line.charAt(i) == ' ')
			i++;

		return i;
	}

	private static String withoutComment(String value) {
		if (value == null)
			return "";

		final String trimmed = value.strip();

		if (trimmed.startsWith("#"))
			return "";

		char quote = 0;

		for (int i = 0; i < trimmed.length(); i++) {
			final char c = trimmed.charAt(i);

			if (quote != 0) {
				if (c == '\\' && quote == '"')
					i++;
				else if (c == quote)
					quote = 0;

			} else if (c == '"' || c == '\'')
				quote = c;
			else if (c == '#' && i > 0 && Character.isWhitespace(trimmed.charAt(i - 1)))
				return trimmed.substring(0, i).strip();
		}

		return trimmed;
	}

	/** The keys at one level of the file, from line {@code from} to {@code to}, each with its children when it is a section. */
	private static List<Node> parse(List<String> lines, int from, int to, int indent) {
		final List<Node> nodes = new ArrayList<>();
		int i = from;

		while (i < to) {
			final String line = lines.get(i);

			if (trivia(line) || indentOf(line) != indent) {
				i++;

				continue;
			}

			final Matcher match = KEY_LINE.matcher(line);

			if (!match.matches()) {
				i++;

				continue;
			}

			final Node node = new Node(match.group(2), indent, i, withoutComment(match.group(3)));

			// The block of a key: the lines that are indented more, and the items of a list written at the key's own indent.
			int end = i + 1;
			int last = i + 1;

			while (end < to) {
				final String next = lines.get(end);

				if (trivia(next)) {
					end++;

					continue;
				}

				final int nextIndent = indentOf(next);

				if (nextIndent > indent || (nextIndent == indent && node.inline.isEmpty() && (next.startsWith(" ".repeat(indent) + "- ") || next.strip().equals("-")))) {
					end++;
					last = end;

					continue;
				}

				break;
			}

			node.end = last;

			if (node.inline.isEmpty() && last > i + 1) {
				int childIndent = -1;

				for (int k = i + 1; k < last; k++)
					if (!trivia(lines.get(k))) {
						childIndent = indentOf(lines.get(k));

						break;
					}

				if (childIndent > indent && !lines.get(i + 1 + firstContent(lines, i + 1, last)).strip().startsWith("-")) {
					final List<Node> children = parse(lines, i + 1, last, childIndent);

					if (!children.isEmpty())
						node.children = children;
				}
			}

			nodes.add(node);
			i = last;
		}

		return nodes;
	}

	private static int firstContent(List<String> lines, int from, int to) {
		for (int k = from; k < to; k++)
			if (!trivia(lines.get(k)))
				return k - from;

		return 0;
	}

	private static Node find(List<Node> nodes, String norm) {
		for (final Node node : nodes)
			if (node.norm.equals(norm))
				return node;

		return null;
	}

	/** The template's lines for one level, with each key's value replaced by the old file's. */
	private static void merge(List<String> template, List<Node> templateNodes, int from, int to, List<String> old, List<Node> oldNodes, String path, String versionNorm, List<String> out, List<String> added, List<String> dropped) {
		int cursor = from;

		if (oldNodes != null)
			for (final Node oldNode : oldNodes)
				if (find(templateNodes, oldNode.norm) == null)
					dropped.add(path + oldNode.key);

		for (final Node node : templateNodes) {
			// Comments and blank lines above the key come from the template.
			for (; cursor < node.start; cursor++)
				out.add(template.get(cursor));

			final Node oldNode = oldNodes == null ? null : find(oldNodes, node.norm);

			if (oldNode != null && path.isEmpty() && node.norm.equals(versionNorm)) {
				// The layout version is the template's: it says which layout the file now has.
				for (int k = node.start; k < node.end; k++)
					out.add(template.get(k));

			} else if (oldNode == null) {
				added.add(path + node.key);

				for (int k = node.start; k < node.end; k++)
					out.add(template.get(k));

			} else if (node.children != null && oldNode.children != null) {
				out.add(template.get(node.start));
				merge(template, node.children, node.start + 1, node.end, old, oldNode.children, path + node.key + ".", versionNorm, out, added, dropped);

			} else if (node.children != null) {
				// The template has a section here and the old file has something else: nothing of it can be carried over.
				dropped.add(path + node.key + " (not a section in the old file)");

				for (int k = node.start; k < node.end; k++)
					out.add(template.get(k));

				collectAdded(node.children, path + node.key + ".", added);

			} else {
				// A value, a list or a map the admin owns: theirs, as they wrote it, moved to where the template has the key.
				final int delta = node.indent - oldNode.indent;

				for (int k = oldNode.start; k < oldNode.end; k++) {
					String line = old.get(k);

					if (k == oldNode.start)
						line = " ".repeat(oldNode.indent) + node.key + line.substring(oldNode.indent + oldNode.key.length());

					out.add(reindent(line, delta));
				}
			}

			cursor = node.end;
		}

		for (; cursor < to; cursor++)
			out.add(template.get(cursor));
	}

	private static void collectAdded(List<Node> nodes, String path, List<String> added) {
		for (final Node node : nodes)
			added.add(path + node.key);
	}

	private static String reindent(String line, int delta) {
		if (delta == 0 || line.isBlank())
			return line;

		if (delta > 0)
			return " ".repeat(delta) + line;

		return line.substring(Math.min(-delta, indentOf(line)));
	}

	private static Integer versionOf(List<Node> nodes, List<String> lines, String versionKey) {
		if (versionKey == null || nodes == null)
			return null;

		final Node node = find(nodes, normalize(versionKey));

		if (node == null)
			return null;

		try {
			return Integer.parseInt(node.inline.replace("\"", "").replace("'", "").strip());

		} catch (final NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * @param file       the admin's file; nothing happens when it does not exist
	 * @param template   the file shipped in the jar, as text
	 * @param versionKey the key that holds the layout version ({@code config-version}, {@code Version}), or null for none
	 * @param log        receives one line for the migration, and one for what was not carried over
	 * @throws IOException when the file cannot be read, backed up or written; the file is then as it was
	 */
	public static Result update(Path file, String template, String versionKey, Consumer<String> log) throws IOException {
		if (!Files.isRegularFile(file))
			return new Result(Status.UNCHANGED, List.of(), List.of(), null);

		final String oldText = Files.readString(file, StandardCharsets.UTF_8);
		final List<String> oldLines = lines(oldText);
		final List<String> templateLines = lines(template);

		final List<Node> oldNodes = parse(oldLines, 0, oldLines.size(), 0);
		final List<Node> templateNodes = parse(templateLines, 0, templateLines.size(), 0);

		if (oldNodes.isEmpty() || templateNodes.isEmpty())
			return new Result(Status.UNCHANGED, List.of(), List.of(), null);

		final List<String> out = new ArrayList<>();
		final List<String> added = new ArrayList<>();
		final List<String> dropped = new ArrayList<>();

		merge(templateLines, templateNodes, 0, templateLines.size(), oldLines, oldNodes, "", versionKey == null ? null : normalize(versionKey), out, added, dropped);

		final Integer oldVersion = versionOf(oldNodes, oldLines, versionKey);
		final Integer newVersion = versionOf(templateNodes, templateLines, versionKey);
		final boolean olderVersion = newVersion != null && (oldVersion == null || oldVersion < newVersion);

		if (added.isEmpty() && !olderVersion)
			return new Result(Status.UNCHANGED, List.of(), List.copyOf(dropped), null);

		final Path backup = backupPath(file);
		Files.copy(file, backup);

		final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

		Files.writeString(temporary, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);

		log.accept("Updated " + file.getFileName() + " to the layout of this version" + (oldVersion == null ? "" : " (from version " + oldVersion + ")") + ": "
				+ (added.isEmpty() ? "no new keys" : "new keys: " + String.join(", ", added)) + ". Your values were kept; the old file is saved as " + backup.getFileName() + ".");

		if (!dropped.isEmpty())
			log.accept(file.getFileName() + ": not carried over because this version no longer has them: " + String.join(", ", dropped) + " (they are in " + backup.getFileName() + ").");

		return new Result(Status.UPDATED, List.copyOf(added), List.copyOf(dropped), backup);
	}

	private static List<String> lines(String text) {
		final List<String> lines = new ArrayList<>(List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));

		if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty())
			lines.remove(lines.size() - 1);

		return lines;
	}

	private static Path backupPath(Path file) {
		Path backup = file.resolveSibling(file.getFileName() + ".bak");

		for (int i = 1; Files.exists(backup); i++)
			backup = file.resolveSibling(file.getFileName() + ".bak." + i);

		return backup;
	}
}
