#!/bin/bash
# Checks the two built plugin jars: only our own packages and the relocated libraries may be inside, nothing that the server provides
# (Velocity, Paper, Adventure, SLF4J), the descriptors must name a main class that is really in the jar, and every library must be relocated.
# Usage: tools/check-jars.sh   (run `mvn package` first)
set -u

root="$(cd "$(dirname "$0")/.." && pwd)"
velocity="$root/authenticator-velocity/target/Authenticator-Velocity-1.0.0.jar"
paper="$root/authenticator-paper/target/Authenticator-Paper-1.0.0.jar"
failed=0

fail() { echo "FAIL: $1"; failed=1; }

for jar in "$velocity" "$paper"; do
	[ -f "$jar" ] || { echo "missing $jar: run mvn package first"; exit 2; }
done

entries() { unzip -Z1 "$1" | grep -v '/$'; }

# 1. Every class lives under our package (the libraries relocated into it), and nothing the server provides is inside.
for jar in "$velocity" "$paper"; do
	name="$(basename "$jar")"
	strays="$(entries "$jar" | grep '\.class$' | grep -v '^pl/kingdomcraft/authenticator/' | grep -v '^META-INF/versions/')"
	[ -z "$strays" ] || fail "$name has classes outside pl/kingdomcraft/authenticator/: $(echo "$strays" | sed 's|/[^/]*$||' | sort -u | head -5 | tr '\n' ' ')"

	for provided in net/kyori/adventure io/papermc com/velocitypowered org/bukkit org/spigotmc org/slf4j com/google/inject; do
		entries "$jar" | grep -q "^$provided/" && fail "$name contains $provided, which the server provides"
	done

	# multi-release class files are only fine when they belong to a relocated library
	odd="$(entries "$jar" | grep '^META-INF/versions/' | grep -v '^META-INF/versions/[0-9]*/pl/kingdomcraft/authenticator/' | grep '\.class$' | grep -v module-info)"
	[ -z "$odd" ] || fail "$name has unrelocated multi-release classes: $(echo "$odd" | head -3 | tr '\n' ' ')"
done

# 2. Services files name relocated classes only.
for jar in "$velocity" "$paper"; do
	for service in $(entries "$jar" | grep '^META-INF/services/'); do
		bad="$(unzip -p "$jar" "$service" | grep -v '^#' | grep -v '^$' | grep -v '^pl\.kingdomcraft\.authenticator\.')"
		[ -z "$bad" ] || fail "$(basename "$jar") $service names an unrelocated class: $bad"
	done
done

# 3. Descriptors.
main_v="$(unzip -p "$velocity" velocity-plugin.json | sed -n 's/.*"main":"\([^"]*\)".*/\1/p')"
[ -n "$main_v" ] || fail "velocity-plugin.json has no main class"
entries "$velocity" | grep -qx "$(echo "$main_v" | tr . /).class" || fail "velocity main class $main_v is not in the jar"

main_p="$(unzip -p "$paper" plugin.yml | sed -n 's/^main: *//p')"
[ -n "$main_p" ] || fail "plugin.yml has no main class"
entries "$paper" | grep -qx "$(echo "$main_p" | tr . /).class" || fail "paper main class $main_p is not in the jar"
unzip -p "$paper" plugin.yml | grep -q '\${' && fail "plugin.yml still has an unreplaced \${...}"
unzip -p "$paper" settings.yml | grep -v '^#' | grep -q '\${' && fail "settings.yml still has an unreplaced \${...} outside comments"

# 4. Resources the plugins read at run time.
entries "$velocity" | grep -qx 'config.yml' || fail "velocity jar has no config.yml"
for lang in pl en; do
	entries "$velocity" | grep -qx "pl/kingdomcraft/authenticator/common/message/messages_$lang.properties" || fail "velocity jar lacks the $lang messages"
	entries "$paper" | grep -qx "localization/messages_$lang.yml" || fail "paper jar lacks localization/messages_$lang.yml"
done
entries "$paper" | grep -qx 'settings.yml' || fail "paper jar has no settings.yml"
for jar in "$velocity" "$paper"; do
	entries "$jar" | grep -qx 'db/mariadb/migrations.list' || fail "$(basename "$jar") lacks the database migrations"
done

# 5. Every library is relocated: the shaded prefix holds each of them.
for jar in "$velocity" "$paper"; do
	for lib in hikari mariadb redis caffeine gson password4j; do
		entries "$jar" | grep -q "^pl/kingdomcraft/authenticator/libs/$lib/" || fail "$(basename "$jar") lacks the relocated $lib"
	done
done
entries "$paper" | grep -q '^pl/kingdomcraft/authenticator/lib/command/' || fail "paper jar lacks the relocated Foundation"
entries "$velocity" | grep -q '^pl/kingdomcraft/authenticator/libs/configurate/' || fail "velocity jar lacks the relocated Configurate"

if [ "$failed" = 0 ]; then
	echo "OK: both jars hold only our own packages and relocated libraries; descriptors and resources are right."
	echo "    velocity: $(entries "$velocity" | wc -l) entries, main $main_v"
	echo "    paper:    $(entries "$paper" | wc -l) entries, main $main_p"
fi

exit $failed
