#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
mkdir -p target/example-classes
find examples/src -name '*.java' -print | sort > target/example-sources.txt
javac --release 21 -Xlint:all -Werror -cp target/simple-di.jar \
  -d target/example-classes @target/example-sources.txt
java -cp target/simple-di.jar:target/example-classes example.Demo
