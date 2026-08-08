#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
VERSION=2.5.0
JAR_DATE="${JAR_DATE:-2026-08-02T00:00:00Z}"

./test.sh
rm -rf dist target/javadoc target/source-stage
mkdir -p dist target/javadoc target/source-stage

cp target/simple-di.jar "dist/simple-di-${VERSION}.jar"
cp -R src/main/java/. target/source-stage/
if [[ -d src/main/resources ]]; then cp -R src/main/resources/. target/source-stage/; fi

source_args=()
while IFS= read -r file; do
  relative="${file#target/source-stage/}"
  source_args+=( -C target/source-stage "$relative" )
done < <(find target/source-stage -type f -print | sort)
jar --create --date="$JAR_DATE" --file "dist/simple-di-${VERSION}-sources.jar" "${source_args[@]}"

javadoc --release 21 -quiet -notimestamp -Xdoclint:all,-missing \
  -d target/javadoc @target/main-sources.txt
javadoc_args=()
while IFS= read -r file; do
  relative="${file#target/javadoc/}"
  javadoc_args+=( -C target/javadoc "$relative" )
done < <(find target/javadoc -type f -print | sort)
jar --create --date="$JAR_DATE" --file "dist/simple-di-${VERSION}-javadoc.jar" "${javadoc_args[@]}"

(
  cd dist
  sha256sum "simple-di-${VERSION}.jar" \
    "simple-di-${VERSION}-sources.jar" \
    "simple-di-${VERSION}-javadoc.jar" > SHA256SUMS
)
echo "Built dist artifacts for simple-di ${VERSION}"
