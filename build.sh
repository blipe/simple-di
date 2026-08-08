#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf target
mkdir -p target/classes target/test-classes
find src/main/java -name '*.java' -print | sort > target/main-sources.txt
javac --release 21 -Xlint:all -Werror -d target/classes @target/main-sources.txt
if [[ -d src/main/resources ]]; then cp -R src/main/resources/. target/classes/; fi
cat > target/MANIFEST.MF <<'MANIFEST'
Manifest-Version: 1.0
Implementation-Title: simple-di
Implementation-Version: 2.5.0
MANIFEST
JAR_DATE="${JAR_DATE:-2026-08-02T00:00:00Z}"
jar_args=()
while IFS= read -r file; do
  relative="${file#target/classes/}"
  jar_args+=( -C target/classes "$relative" )
done < <(find target/classes -type f -print | sort)
jar --create --date="$JAR_DATE" --file target/simple-di.jar \
  --manifest target/MANIFEST.MF "${jar_args[@]}"
echo "Built target/simple-di.jar"
