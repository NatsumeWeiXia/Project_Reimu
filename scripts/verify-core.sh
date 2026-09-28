#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p evidence/core-classes
find core/src/main/java -name '*.java' | sort > evidence/core-sources.txt
printf '%s\n' core/src/test/java/io/reimu/core/CoreSpec.java >> evidence/core-sources.txt
javac -encoding UTF-8 --release 17 -Xlint:all -d evidence/core-classes @evidence/core-sources.txt
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -ea -cp evidence/core-classes io.reimu.core.CoreSpec | tee evidence/core-test.log
