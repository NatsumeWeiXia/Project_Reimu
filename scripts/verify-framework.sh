#!/usr/bin/env bash
# Optional offline verification using genuine, independently supplied Spring/H2 JARs.
# Does NOT build the AgentScope module or the React application.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${REIMU_FRAMEWORK_CLASSPATH:?Provide genuine Spring Boot 3.5.5 + H2 dependency JAR classpath}"
mkdir -p .verification/server-classes evidence
find core/src/main/java server/src/main/java -name '*.java' > .verification/server-sources.txt
javac --release 17 -parameters -encoding UTF-8 -cp "$REIMU_FRAMEWORK_CLASSPATH" -d .verification/server-classes @.verification/server-sources.txt
cp server/src/main/resources/application.yml .verification/server-classes/
export REIMU_MODE=demo REIMU_SECURE_COOKIE=false PORT=8787 REIMU_ORIGIN=http://127.0.0.1:8787
export REIMU_USERS_JSON='{"alice":{"token":"ci-only-alice-test-key-00000000","admin":true},"bob":{"token":"ci-only-bob-test-key-0000000000","admin":false}}'
export DB_URL="jdbc:h2:file:$(pwd)/.verification/test-$(date +%s);DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
export REIMU_TEST_SERVER_COMMAND="java -Dfile.encoding=UTF-8 -cp .verification/server-classes:$REIMU_FRAMEWORK_CLASSPATH io.reimu.server.App"
python3 scripts/verify-http.py | tee evidence/http-test.log
