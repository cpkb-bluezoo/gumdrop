#!/usr/bin/env bash
# Builds and deploys every Gumdrop Maven coordinate to GitHub Packages.
#
# Publishes the same artifacts, with the same POMs, as publish-to-central.sh
# (the list and the version guards live in release-artifacts.sh). Each module
# is deployed together with its own POM from central/, so a consumer who
# depends on one artifact gets exactly the Gumdrop modules and libraries that
# module needs, resolved at the same version. Deploying a bare jar would make
# Maven generate an empty POM and lose those dependencies.
#
# Usage (in CI the workflow supplies the credentials through settings.xml):
#   GITHUB_ACTOR=... GITHUB_TOKEN=... ./scripts/publish-to-github-packages.sh
#
# Optional:
#   VERSION     - release version (default: build.xml version property)
#   DRY_RUN=1   - build nothing and deploy nothing; print the commands only
#   SKIP_BUILD=1 - use the existing dist/ instead of running ant
#   MVN         - the Maven command (default: mvn)
#
# Unlike Maven Central, GitHub Packages accepts SNAPSHOT versions, so those
# are allowed. Run it from the repository root. It does not commit, tag or
# push anything.

set -euo pipefail

REPO_URL="${GITHUB_PACKAGES_URL:-https://maven.pkg.github.com/cpkb-bluezoo/gumdrop}"
REPOSITORY_ID="${GITHUB_PACKAGES_ID:-github}"
DEPLOY_PLUGIN="org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file"
MVN="${MVN:-mvn}"

# shellcheck source=release-artifacts.sh
. "$(dirname "$0")/release-artifacts.sh"

resolve_version

run() {
    if [ -n "${DRY_RUN:-}" ]; then
        printf '+'
        printf ' %q' "$@"
        printf '\n'
    else
        "$@"
    fi
}

if [ -z "${DRY_RUN:-}" ] && [ -z "${SKIP_BUILD:-}" ]; then
    echo "==> Building release artifacts (version $VERSION)"
    ant release-central -Dversion="$VERSION"
fi

if [ -z "${DRY_RUN:-}" ]; then
    verify_dist
fi

SHARED_SOURCES_JAR="dist/gumdrop-${VERSION}-sources.jar"
SHARED_JAVADOC_JAR="dist/gumdrop-${VERSION}-javadoc.jar"

echo "==> Deploying ${#ARTIFACTS[@]} coordinates to $REPO_URL (version $VERSION)"

for entry in "${ARTIFACTS[@]}"; do
    IFS=':' read -r ARTIFACT_ID POM_FILE PACKAGING SOURCES_MODE <<< "$entry"
    SOURCES_MODE="${SOURCES_MODE:-shared}"

    args=(
        "$MVN" -B -q "$DEPLOY_PLUGIN"
        "-DrepositoryId=$REPOSITORY_ID"
        "-Durl=$REPO_URL"
        "-DpomFile=$POM_FILE"
        "-DgeneratePom=false"
    )

    if [ "$PACKAGING" = "pom" ]; then
        # A POM-only artifact (the BOM): the POM is also the "file".
        args+=("-Dfile=$POM_FILE" "-Dpackaging=pom")
    else
        args+=("-Dfile=dist/$ARTIFACT_ID-$VERSION.$PACKAGING" "-Dpackaging=$PACKAGING")
        case "$SOURCES_MODE" in
            own)    args+=("-Dsources=dist/$ARTIFACT_ID-$VERSION-sources.jar") ;;
            shared) args+=("-Dsources=$SHARED_SOURCES_JAR") ;;
            none)   ;;
            *)
                echo "error: unknown sources mode: $SOURCES_MODE" >&2
                exit 1
                ;;
        esac
        args+=("-Djavadoc=$SHARED_JAVADOC_JAR")
    fi

    echo "  $ARTIFACT_ID"
    run "${args[@]}"
done

echo "==> Deployed ${#ARTIFACTS[@]} coordinates"
