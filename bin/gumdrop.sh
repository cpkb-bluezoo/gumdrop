#!/bin/sh
#
# Gumdrop servlet container launcher (lib/ distribution layout).
#
# Expects:
#   GUMDROP_HOME/   (defaults to the parent of this script's bin/ directory)
#     lib/gumdrop-bootstrap.jar
#     lib/gumdrop.jar
#     lib/*.jar       (J2EE APIs and other dependencies)
#
# Environment (same as start):
#   JAVA, GUMDROP_CONFIG, LOGGING_PROPERTIES, JAVA_OPTS, MAX_RAM_PERCENTAGE

set -e

if [ -z "$GUMDROP_HOME" ]; then
	GUMDROP_HOME=$(cd "$(dirname "$0")/.." && pwd)
	export GUMDROP_HOME
fi

java="${JAVA:-java}"
LIB="$GUMDROP_HOME/lib"
BOOTSTRAP="$LIB/gumdrop-bootstrap.jar"

if [ ! -f "$BOOTSTRAP" ]; then
	echo "gumdrop: missing $BOOTSTRAP (run ant assemble-container or unpack the distribution zip)" >&2
	exit 1
fi

LOGGING_PROPERTIES="${LOGGING_PROPERTIES:-$GUMDROP_HOME/logging.properties}"
MAX_RAM_PERCENTAGE="${MAX_RAM_PERCENTAGE:-75.0}"

# Java 25+: cgroup/container memory limits are automatic; +UseContainerSupport was removed.
jvm_opts="-XX:MaxRAMPercentage=$MAX_RAM_PERCENTAGE"

# $JAVA_OPTS is deliberately left unquoted so it splits into separate options;
# the logging path is quoted so a GUMDROP_HOME containing spaces survives.
if [ -n "$LOGGING_PROPERTIES" ] && [ -f "$LOGGING_PROPERTIES" ]; then
	exec "$java" $jvm_opts "-Djava.util.logging.config.file=$LOGGING_PROPERTIES" \
		$JAVA_OPTS \
		-cp "$BOOTSTRAP" org.bluezoo.gumdrop.Bootstrap "$@"
fi
exec "$java" $jvm_opts \
	$JAVA_OPTS \
	-cp "$BOOTSTRAP" org.bluezoo.gumdrop.Bootstrap "$@"
