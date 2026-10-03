#!/usr/bin/env bash
# Builds, signs, bundles, and uploads a release to Maven Central's Publisher
# API. The single canonical implementation of this process - both the
# release-to-maven-central.yml workflow and a maintainer running this by
# hand locally call this same script.
#
# Gumdrop publishes modular library jars, container/manager distribution
# artifacts, and the gumdrop-j2ee-bom from one source tree. POMs live in
# central/*-pom.xml (unrelated to the root pom.xml used for GitHub Packages).
#
# Library jars share one javadoc build and (except container/manager) reuse the
# all-in-one gumdrop-*-sources.jar for Maven Central source attachments.
#
# Usage:
#   GPG_KEY_ID=... \
#   GPG_PASSPHRASE=... \
#   CENTRAL_TOKEN_USERNAME=... \
#   CENTRAL_TOKEN_PASSWORD=... \
#   ./scripts/publish-to-central.sh
#
# Required environment variables:
#   GPG_KEY_ID, GPG_PASSPHRASE, CENTRAL_TOKEN_USERNAME, CENTRAL_TOKEN_PASSWORD
#
# Optional:
#   VERSION         - release version (default: build.xml version property)
#   PUBLISHING_TYPE - AUTOMATIC (default) or USER_MANAGED
#
# This script does not commit, tag, or push anything.

set -euo pipefail

for var in GPG_KEY_ID GPG_PASSPHRASE CENTRAL_TOKEN_USERNAME CENTRAL_TOKEN_PASSWORD; do
    if [ -z "${!var:-}" ]; then
        echo "error: $var must be set" >&2
        exit 1
    fi
done

# The artifact list and the version guards are shared with
# publish-to-github-packages.sh so both registries publish the same thing.
# shellcheck source=release-artifacts.sh
. "$(dirname "$0")/release-artifacts.sh"

resolve_version
reject_snapshot

PUBLISHING_TYPE="${PUBLISHING_TYPE:-AUTOMATIC}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Building release artifacts (version $VERSION)"
ant release-central -Dversion="$VERSION"

SHARED_SOURCES_JAR="dist/gumdrop-${VERSION}-sources.jar"
SHARED_JAVADOC_JAR="dist/gumdrop-${VERSION}-javadoc.jar"
verify_dist

sign_bundle_files() {
    local dir="$1"
    (
        cd "$dir"
        for f in *.jar *.war *.pom; do
            [ -e "$f" ] || continue
            gpg --batch --local-user "$GPG_KEY_ID" --pinentry-mode loopback \
                --passphrase "$GPG_PASSPHRASE" -ab "$f"
            md5sum "$f" | cut -d' ' -f1 > "$f.md5"
            shasum -a 1 "$f" | cut -d' ' -f1 > "$f.sha1"
        done
    )
}

echo "==> Assembling and signing Central bundle"

for entry in "${ARTIFACTS[@]}"; do
    IFS=':' read -r ARTIFACT_ID POM_FILE PACKAGING SOURCES_MODE <<< "$entry"
    SOURCES_MODE="${SOURCES_MODE:-shared}"

    BUNDLE_DIR="$WORKDIR/bundle/org/bluezoo/$ARTIFACT_ID/$VERSION"
    mkdir -p "$BUNDLE_DIR"
    cp "$POM_FILE" "$BUNDLE_DIR/$ARTIFACT_ID-$VERSION.pom"

    if [ "$PACKAGING" = "pom" ]; then
        sign_bundle_files "$BUNDLE_DIR"
        continue
    fi

    MAIN_FILE="dist/$ARTIFACT_ID-$VERSION.$PACKAGING"
    if [ ! -f "$MAIN_FILE" ]; then
        echo "error: missing release artifact: $MAIN_FILE" >&2
        exit 1
    fi
    cp "$MAIN_FILE" "$BUNDLE_DIR/"

    case "$SOURCES_MODE" in
        own)
            OWN_SOURCES="dist/$ARTIFACT_ID-$VERSION-sources.jar"
            if [ ! -f "$OWN_SOURCES" ]; then
                echo "error: missing sources jar: $OWN_SOURCES" >&2
                exit 1
            fi
            cp "$OWN_SOURCES" "$BUNDLE_DIR/"
            ;;
        shared)
            cp "$SHARED_SOURCES_JAR" "$BUNDLE_DIR/$ARTIFACT_ID-$VERSION-sources.jar"
            ;;
        none)
            ;;
        *)
            echo "error: unknown sources mode: $SOURCES_MODE" >&2
            exit 1
            ;;
    esac

    cp "$SHARED_JAVADOC_JAR" "$BUNDLE_DIR/$ARTIFACT_ID-$VERSION-javadoc.jar"
    sign_bundle_files "$BUNDLE_DIR"
done

BUNDLE_ZIP="$WORKDIR/central-bundle.zip"
(cd "$WORKDIR/bundle" && zip -qr "$BUNDLE_ZIP" .)
echo "Bundle assembled at $BUNDLE_ZIP (${#ARTIFACTS[@]} coordinates):"
unzip -l "$BUNDLE_ZIP" | head -40
echo "..."

if [ -n "${KEEP_BUNDLE_AT:-}" ]; then
    cp "$BUNDLE_ZIP" "$KEEP_BUNDLE_AT"
fi

echo "==> Uploading to Maven Central (publishingType=$PUBLISHING_TYPE)"
TOKEN=$(printf '%s:%s' "$CENTRAL_TOKEN_USERNAME" "$CENTRAL_TOKEN_PASSWORD" | base64 | tr -d '\n')
DEPLOYMENT_ID=$(curl --fail --request POST \
    -H "Authorization: Bearer $TOKEN" \
    --form bundle=@"$BUNDLE_ZIP" \
    "https://central.sonatype.com/api/v1/publisher/upload?publishingType=$PUBLISHING_TYPE")

echo
echo "==> Uploaded. Deployment ID: $DEPLOYMENT_ID"
if [ "$PUBLISHING_TYPE" = "USER_MANAGED" ]; then
    echo "This will NOT go live until you review and click Publish at:"
    echo "  https://central.sonatype.com/publishing/deployments"
fi
