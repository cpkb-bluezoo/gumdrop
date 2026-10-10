#!/bin/bash
# Build and run the JSP Code Generation example.
#
# Usage: ./build.sh [input.jsp output.java [encoding]]
#
# Needs the container distribution: run "ant assemble-container" in the
# project root first. Override with GUMDROP_LIB to point at another lib/.

set -e

cd "$(dirname "$0")"

LIB="${GUMDROP_LIB:-../../dist/container-home/lib}"

echo "JSP Code Generation Example - Build Script"
echo "=========================================="

if [ ! -f "$LIB/gumdrop-servlet.jar" ]; then
    echo "Error: $LIB/gumdrop-servlet.jar not found."
    echo "Build the distribution first by running 'ant assemble-container' in the project root."
    exit 1
fi

CLASSPATH="$LIB/*"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

echo "Compiling JSPCodeGeneratorExample.java..."
javac -d "$OUT" -cp "$CLASSPATH" JSPCodeGeneratorExample.java
echo "✓ Compilation successful!"

echo ""
echo "Running example..."
echo "=================="
java -cp "$OUT:$CLASSPATH" JSPCodeGeneratorExample "$@"

# With no arguments the example regenerates the checked-in sample.
if [ $# -eq 0 ]; then
    echo ""
    echo "Checking that the generated servlet compiles..."
    javac -d "$OUT" -cp "$CLASSPATH" TestExample_jsp.java
    echo "✓ Generated servlet compiles!"
fi

echo ""
echo "To run with your own JSP file:"
echo "  ./build.sh input.jsp Output_jsp.java"
echo "(name the output file after the generated class, e.g. Hello_jsp.java for hello.jsp)"
