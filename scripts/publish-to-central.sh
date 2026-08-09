#!/usr/bin/env bash
# Builds, signs, bundles, and uploads a release to Maven Central's Publisher
# API. The single canonical implementation of this process - both the
# release-to-maven-central.yml workflow and a maintainer running this by
# hand locally call this same script, so there is exactly one place that
# knows how to do this, not two copies that can drift apart.
#
# Gumdrop publishes three separate Maven coordinates from one source tree:
#   org.bluezoo:gumdrop            - core server framework (jar)
#   org.bluezoo:gumdrop-container  - self-contained bootstrap jar (jar);
#                                     bundles gumdrop.jar and all its
#                                     dependencies internally via its own
#                                     classloader, so it declares NO Maven
#                                     dependencies of its own (see its POM)
#   org.bluezoo:gumdrop-manager    - manager web application (war)
# All three are built from the same `ant release-central` invocation and
# uploaded together in a single Central bundle. Their POMs live in
# central/gumdrop-pom.xml, central/gumdrop-container-pom.xml, and
# central/gumdrop-manager-pom.xml - unrelated to the root pom.xml, which is
# a separate distribution POM used only for GitHub Packages.
#
# Central requires a javadoc jar per artifact, but this project only
# generates one javadoc build (covering the core source tree, the only one
# with real Java API surface) - this script copies that single jar into
# each of the three bundles under the appropriate artifact-specific
# filename, same as its content isn't scoped per-artifact, unlike the
# sources jars, which genuinely differ.
#
# Usage:
#   GPG_KEY_ID=... \
#   GPG_PASSPHRASE=... \
#   CENTRAL_TOKEN_USERNAME=... \
#   CENTRAL_TOKEN_PASSWORD=... \
#   ./scripts/publish-to-central.sh
#
# Required environment variables:
#   GPG_KEY_ID               - key ID (or fingerprint) to sign with; must
#                               already be in the local/CI GPG keyring
#   GPG_PASSPHRASE            - passphrase for that key
#   CENTRAL_TOKEN_USERNAME   - Sonatype Central user token username
#   CENTRAL_TOKEN_PASSWORD   - Sonatype Central user token password
#
# Optional:
#   VERSION                  - the release version, e.g. 2.1.0 - if unset,
#                               auto-detected from build.xml's own "version"
#                               property. Either way (given or auto-detected),
#                               it is cross-checked against all three
#                               central/*-pom.xml files before building
#                               anything - see the mismatch check below.
#                               MUST NOT be a SNAPSHOT version - Maven
#                               Central does not accept snapshot publishes
#                               at all, so this script refuses to run
#                               against one (bump build.xml's version, and
#                               all three central/*-pom.xml files, together
#                               when cutting a new release).
#   PUBLISHING_TYPE          - AUTOMATIC or USER_MANAGED (default:
#                               AUTOMATIC - goes live on Central as soon as
#                               validation passes, no manual "Publish" click
#                               needed. A released version can never be
#                               deleted or overwritten, so only rely on this
#                               once the pipeline is trusted; set
#                               PUBLISHING_TYPE=USER_MANAGED to fall back to
#                               reviewing and clicking Publish by hand at
#                               central.sonatype.com)
#
# This script does not commit, tag, or push anything - it only builds
# whatever is currently checked out and publishes it under the given
# VERSION. Run it from the repository root.

set -euo pipefail

for var in GPG_KEY_ID GPG_PASSPHRASE CENTRAL_TOKEN_USERNAME CENTRAL_TOKEN_PASSWORD; do
    if [ -z "${!var:-}" ]; then
        echo "error: $var must be set" >&2
        exit 1
    fi
done

BUILD_XML_VERSION=$(grep -m1 "name='version'" build.xml | sed -E "s/.*value='([^']*)'.*/\1/")
if [ -z "$BUILD_XML_VERSION" ]; then
    echo "error: could not read the 'version' property from build.xml" >&2
    exit 1
fi

if [ -z "${VERSION:-}" ]; then
    VERSION="$BUILD_XML_VERSION"
    echo "==> Auto-detected VERSION=$VERSION from build.xml"
fi

case "$VERSION" in
    *SNAPSHOT*)
        echo "error: VERSION=$VERSION is a SNAPSHOT version - Maven Central does not accept snapshot publishes" >&2
        echo "       cut a real release version in build.xml and all three central/*-pom.xml files first" >&2
        exit 1
        ;;
esac

# build.xml's "version" property drives the actual Ant-built jar/war
# filenames, so it's the authoritative source - each of the three Central
# POMs' own <version> must agree with it (and with each other), or we'd
# end up bundling a POM whose declared version mismatches its Maven
# coordinate.
if [ "$BUILD_XML_VERSION" != "$VERSION" ]; then
    echo "error: version mismatch - requested VERSION=$VERSION, but build.xml's 'version' property says $BUILD_XML_VERSION" >&2
    echo "       fix whichever one is stale before publishing" >&2
    exit 1
fi

for pom in central/gumdrop-pom.xml central/gumdrop-container-pom.xml central/gumdrop-manager-pom.xml; do
    POM_VERSION=$(grep -m1 '<version>' "$pom" | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
    if [ "$POM_VERSION" != "$VERSION" ]; then
        echo "error: version mismatch - VERSION=$VERSION, but $pom's <version> says $POM_VERSION" >&2
        echo "       fix whichever one is stale before publishing" >&2
        exit 1
    fi
done

PUBLISHING_TYPE="${PUBLISHING_TYPE:-AUTOMATIC}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Building release artifacts (version $VERSION)"
ant release-central -Dversion="$VERSION"

echo "==> Assembling and signing Central bundle"

# artifactId:pomFile:extension triples - extension differs for the WAR;
# sources/javadoc are always .jar regardless (standard Maven convention).
ARTIFACTS=(
    "gumdrop:central/gumdrop-pom.xml:jar"
    "gumdrop-container:central/gumdrop-container-pom.xml:jar"
    "gumdrop-manager:central/gumdrop-manager-pom.xml:war"
)

SHARED_JAVADOC_JAR="dist/gumdrop-$VERSION-javadoc.jar"
if [ ! -f "$SHARED_JAVADOC_JAR" ]; then
    echo "error: expected shared javadoc jar not found: $SHARED_JAVADOC_JAR" >&2
    exit 1
fi

for entry in "${ARTIFACTS[@]}"; do
    ARTIFACT_ID="${entry%%:*}"
    rest="${entry#*:}"
    POM_FILE="${rest%%:*}"
    EXT="${rest#*:}"

    BUNDLE_DIR="$WORKDIR/bundle/org/bluezoo/$ARTIFACT_ID/$VERSION"
    mkdir -p "$BUNDLE_DIR"

    cp "$POM_FILE" "$BUNDLE_DIR/$ARTIFACT_ID-$VERSION.pom"
    cp "dist/$ARTIFACT_ID-$VERSION.$EXT" "$BUNDLE_DIR/"
    cp "dist/$ARTIFACT_ID-$VERSION-sources.jar" "$BUNDLE_DIR/"
    cp "$SHARED_JAVADOC_JAR" "$BUNDLE_DIR/$ARTIFACT_ID-$VERSION-javadoc.jar"

    (
        cd "$BUNDLE_DIR"
        for f in *.jar *.war *.pom; do
            [ -e "$f" ] || continue
            gpg --batch --local-user "$GPG_KEY_ID" --pinentry-mode loopback \
                --passphrase "$GPG_PASSPHRASE" -ab "$f"
            md5sum "$f" | cut -d' ' -f1 > "$f.md5"
            shasum -a 1 "$f" | cut -d' ' -f1 > "$f.sha1"
        done
    )
done

BUNDLE_ZIP="$WORKDIR/central-bundle.zip"
(cd "$WORKDIR/bundle" && zip -qr "$BUNDLE_ZIP" .)
echo "Bundle assembled at $BUNDLE_ZIP:"
unzip -l "$BUNDLE_ZIP"

# Optional: preserve a copy of the bundle outside the temp workdir (e.g. so
# CI can upload it as an inspectable artifact) before the EXIT trap deletes
# the workdir.
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
