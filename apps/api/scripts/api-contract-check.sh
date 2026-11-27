#!/usr/bin/env bash
# Compares the API contract of the working tree with the last commit, back to back.
#
# 1. The last commit is checked out in a separate git worktree (target/contract-baseline-tree),
#    with the tests and pom.xml of the working tree, and ApiContractSnapshotTest records a
#    baseline from it in target/api-contract-baseline/. The working tree is not touched.
# 2. The same test compares the working tree with that baseline.
#
# Both runs happen within a few minutes, because some answers depend on the hour of the day (see
# ApiContractSnapshotTest). If the hour changes between the two runs, run the script again.
# On a difference, target/api-contract-actual.json holds the answers of the working tree.
set -euo pipefail
cd "$(dirname "$0")/.."

api="$(pwd)"
baseline="$api/target/api-contract-baseline"
tree="$api/target/contract-baseline-tree"

contract() {
  ./mvnw -B -q test -Dtest=ApiContractSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dapi.contract=true -Dsnapshot.dir="$baseline" "$@"
}

cleanup() {
  git worktree remove --force "$tree" > /dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$api/target"
rm -rf "$baseline" "$api/target/api-contract-actual.json"
cleanup
git worktree add --detach --force "$tree" HEAD > /dev/null
# The repository root is above apps/api; the worktree holds the whole repository.
root_rel="$(git rev-parse --show-prefix)"
rm -rf "$tree/$root_rel/src/test"
cp -r "$api/src/test" "$tree/$root_rel/src/test"
cp "$api/pom.xml" "$tree/$root_rel/pom.xml"

echo "Recording the baseline from the last commit..."
if ! (cd "$tree/$root_rel" && contract -Dsnapshot.update=true) > "$api/target/api-contract-baseline.log" 2>&1; then
  echo "The baseline run failed; see target/api-contract-baseline.log"
  exit 1
fi

echo "Comparing the working tree with the baseline..."
if contract > "$api/target/api-contract-check.log" 2>&1; then
  echo "The API contract is unchanged."
else
  echo "The API contract differs (or the run failed); see target/api-contract-check.log"
  if [ -f "$api/target/api-contract-actual.json" ]; then
    diff "$baseline/api-contract.json" "$api/target/api-contract-actual.json" | head -80 || true
  fi
  exit 1
fi
