#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f frontend/package-lock.json ]]; then npm --prefix frontend ci; else npm --prefix frontend install; fi
npm --prefix frontend run test:unit
npm --prefix frontend run build
mkdir -p server/src/main/resources/static
cp -R frontend/dist/. server/src/main/resources/static/
mvn -B verify
