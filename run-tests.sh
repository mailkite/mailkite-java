#!/bin/sh
# Compile + run the dependency-free Java SDK unit tests (no JUnit needed).
set -e
cd "$(dirname "$0")"
rm -rf .test-build
mkdir -p .test-build
javac -d .test-build src/main/java/dev/mailkite/*.java test/SdkTest.java
java -cp .test-build dev.mailkite.test.SdkTest
