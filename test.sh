#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
find src/test/java -name '*.java' -print | sort > target/test-sources.txt
javac --release 21 -Xlint:all -Werror -cp target/classes -d target/test-classes @target/test-sources.txt
if [[ -d src/test/resources ]]; then cp -R src/test/resources/. target/test-classes/; fi
java -ea -cp target/classes:target/test-classes io.github.simpledi.tests.SimpleDiTest
java -ea -cp target/classes:target/test-classes io.github.simpledi.tests.ReloadableTest
