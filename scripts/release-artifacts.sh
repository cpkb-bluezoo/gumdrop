#!/usr/bin/env bash
# Shared by publish-to-central.sh and publish-to-github-packages.sh. Sourced,
# not executed. Defines the one list of Maven coordinates a release publishes
# and the version guards both publishers apply, so the two registries cannot
# drift apart: a consumer must be able to resolve the same artifacts, with the
# same POMs and therefore the same inter-module dependencies, from either.
#
# Each artifact is built by "ant release-central" into dist/ and described by
# its own POM in central/. Library POMs depend on each other as
# ${project.version}, so every coordinate must be published at one version.

# artifactId:pomFile:packaging[:sources-mode]
# sources-mode: shared | own | none  (default shared for jar library artifacts)
ARTIFACTS=(
    "gumdrop:central/gumdrop-pom.xml:jar:shared"
    "gumdrop-core:central/gumdrop-core-pom.xml:jar:shared"
    "gumdrop-mime:central/gumdrop-mime-pom.xml:jar:shared"
    "gumdrop-http:central/gumdrop-http-pom.xml:jar:shared"
    "gumdrop-servlet:central/gumdrop-servlet-pom.xml:jar:shared"
    "gumdrop-mailbox:central/gumdrop-mailbox-pom.xml:jar:shared"
    "gumdrop-telemetry:central/gumdrop-telemetry-pom.xml:jar:shared"
    "gumdrop-ldap:central/gumdrop-ldap-pom.xml:jar:shared"
    "gumdrop-imap:central/gumdrop-imap-pom.xml:jar:shared"
    "gumdrop-smtp:central/gumdrop-smtp-pom.xml:jar:shared"
    "gumdrop-pop3:central/gumdrop-pop3-pom.xml:jar:shared"
    "gumdrop-ftp:central/gumdrop-ftp-pom.xml:jar:shared"
    "gumdrop-amqp:central/gumdrop-amqp-pom.xml:jar:shared"
    "gumdrop-amqp1:central/gumdrop-amqp1-pom.xml:jar:shared"
    "gumdrop-mqtt:central/gumdrop-mqtt-pom.xml:jar:shared"
    "gumdrop-redis:central/gumdrop-redis-pom.xml:jar:shared"
    "gumdrop-grpc:central/gumdrop-grpc-pom.xml:jar:shared"
    "gumdrop-socks:central/gumdrop-socks-pom.xml:jar:shared"
    "gumdrop-webdav:central/gumdrop-webdav-pom.xml:jar:shared"
    "gumdrop-mdns:central/gumdrop-mdns-pom.xml:jar:shared"
    "gumdrop-container:central/gumdrop-container-pom.xml:jar:own"
    "gumdrop-manager:central/gumdrop-manager-pom.xml:war:own"
    "gumdrop-j2ee-bom:central/gumdrop-j2ee-bom-pom.xml:pom:none"
)

# Sets VERSION (from the environment, or build.xml's own "version" property)
# and exits unless build.xml, every central/*-pom.xml, the root pom.xml and the
# BOMs all agree with it.
resolve_version() {
    BUILD_XML_VERSION=$(grep -m1 "name='version'" build.xml | sed -E "s/.*value='([^']*)'.*/\1/")
    if [ -z "$BUILD_XML_VERSION" ]; then
        echo "error: could not read the 'version' property from build.xml" >&2
        exit 1
    fi
    if [ -z "${VERSION:-}" ]; then
        VERSION="$BUILD_XML_VERSION"
        echo "==> Auto-detected VERSION=$VERSION from build.xml"
    fi
    if [ "$BUILD_XML_VERSION" != "$VERSION" ]; then
        echo "error: version mismatch - requested VERSION=$VERSION, but build.xml says $BUILD_XML_VERSION" >&2
        exit 1
    fi
    local pom pom_version
    for pom in central/*-pom.xml pom.xml boms/*/pom.xml; do
        pom_version=$(grep -m1 '<version>' "$pom" | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
        if [ "$pom_version" != "$VERSION" ]; then
            echo "error: version mismatch - VERSION=$VERSION, but $pom says $pom_version" >&2
            exit 1
        fi
    done
}

# Maven Central never accepts a SNAPSHOT.
reject_snapshot() {
    case "$VERSION" in
        *SNAPSHOT*)
            echo "error: VERSION=$VERSION is a SNAPSHOT version - Maven Central does not accept snapshot publishes" >&2
            echo "       cut a real release version in build.xml, pom.xml, boms/*/pom.xml and all central/*-pom.xml files first" >&2
            exit 1
            ;;
    esac
}

# Fails unless every file a release publishes was built into dist/. Prints
# nothing on success. Arguments: none (uses ARTIFACTS and VERSION).
verify_dist() {
    local entry artifact_id pom_file packaging sources_mode
    for entry in "${ARTIFACTS[@]}"; do
        IFS=':' read -r artifact_id pom_file packaging sources_mode <<< "$entry"
        [ "$packaging" = "pom" ] && continue
        if [ ! -f "dist/$artifact_id-$VERSION.$packaging" ]; then
            echo "error: missing release artifact: dist/$artifact_id-$VERSION.$packaging" >&2
            exit 1
        fi
        if [ "${sources_mode:-shared}" = "own" ] && [ ! -f "dist/$artifact_id-$VERSION-sources.jar" ]; then
            echo "error: missing sources jar: dist/$artifact_id-$VERSION-sources.jar" >&2
            exit 1
        fi
    done
    for f in "dist/gumdrop-$VERSION-sources.jar" "dist/gumdrop-$VERSION-javadoc.jar"; do
        if [ ! -f "$f" ]; then
            echo "error: expected shared artifact not found: $f" >&2
            exit 1
        fi
    done
}
