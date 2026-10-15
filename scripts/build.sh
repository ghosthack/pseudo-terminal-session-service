#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TASK_REPO="$PWD/target/oss-repository"
mkdir -p target "$TASK_REPO"
printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>' > target/empty-settings.xml
env -u GH_TOKEN -u GITHUB_TOKEN -u GITHUB_ACTOR \
  mvn -B -ntp -s target/empty-settings.xml -gs target/empty-settings.xml \
    -Dmaven.repo.local="$TASK_REPO" verify "$@"
