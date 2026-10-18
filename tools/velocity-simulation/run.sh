#!/bin/bash
# Compiles the Velocity adapter against hand-written stubs of the Velocity API and drives it through whole sessions on a real MariaDB.
# Needs: JDK 25, Maven, Maven Central, and AUTHENTICATOR_TEST_MARIADB=host:port:user:password (see docs/TESTING.md).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$here/target"

: "${AUTHENTICATOR_TEST_MARIADB:?set AUTHENTICATOR_TEST_MARIADB=host:port:user:password}"

rm -rf "$work" && mkdir -p "$work/classes" "$work/sim"

mvn -q -f "$here/pom.xml" dependency:build-classpath -Dmdep.outputFile="$work/libs.txt"
mvn -q -f "$root/pom.xml" -pl authenticator-common test-compile dependency:build-classpath -Dmdep.outputFile="$work/common-libs.txt"

common="$root/authenticator-common/target"
cp="$(cat "$work/libs.txt"):$(cat "$work/common-libs.txt"):$common/classes:$common/test-classes"

echo "Compiling the adapter against the stubs..."
javac -proc:none -Xlint:all -Xlint:-processing --release 25 -d "$work/classes" -cp "$cp" $(find "$here/stubs" -name '*.java') $(find "$root/authenticator-velocity/src/main/java" -name '*.java')

echo "Compiling the simulation..."
javac -proc:none --release 25 -d "$work/sim" -cp "$cp:$work/classes" "$here/src/Sim.java"

echo "Running it..."
java -cp "$cp:$work/classes:$work/sim" Sim
