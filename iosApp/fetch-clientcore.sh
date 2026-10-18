#!/bin/sh
# Downloads the ClientCore XCFramework that CI built for this commit into Frameworks/, for xtool on Linux:
# Kotlin/Native links Apple binaries only on macOS. Push the commit and wait for CI first.
set -eu
cd "$(dirname "$0")"
commit=$(git rev-parse HEAD)
run=$(gh run list --commit "$commit" --workflow CI --status success --limit 1 --json databaseId --jq '.[0].databaseId')
if [ -z "$run" ]; then
    echo "No successful CI run for $commit yet: push it and wait for CI." >&2
    exit 1
fi
rm -rf Frameworks/ClientCore.xcframework
gh run download "$run" --name ClientCore-xcframework --dir Frameworks
echo "ClientCore from CI run $run ($commit)"
