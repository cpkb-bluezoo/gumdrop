#!/bin/bash
# Build script for JSP Code Generation Example

set -e

GUMDROP_JAR="../../dist/server.jar"
SERVLET_API_JAR="../../lib/javax.servlet-api-4.0.1.jar"

echo "JSP Code Generation Example - Build Script"
echo "=========================================="

# Check if JARs exist
if [ ! -f "$GUMDROP_JAR" ]; then
    echo "Error: Gumdrop JAR not found at $GUMDROP_JAR"
    echo "Please build Gumdrop first by running 'ant dist' in the project root."
    exit 1
fi

if [ ! -f "$SERVLET_API_JAR" ]; then
    echo "Error: Servlet API JAR not found at $SERVLET_API_JAR"
    echo "Please ensure the servlet API is in the lib directory."
    exit 1
fi

echo "Using Gumdrop JAR: $GUMDROP_JAR"
echo "Using Servlet API JAR: $SERVLET_API_JAR"

# Create classpath
CLASSPATH="$GUMDROP_JAR:$SERVLET_API_JAR"

# Compile the example
echo "Compiling JSPCodeGeneratorExample.java..."
javac -cp "$CLASSPATH" JSPCodeGeneratorExample.java

if [ $? -eq 0 ]; then
    echo "✓ Compilation successful!"
    
    # Run the example with default test file
    echo ""
    echo "Running example with test-example.jsp..."
    echo "========================================"
    java -cp ".:$CLASSPATH" JSPCodeGeneratorExample
    
    echo ""
    echo "✓ Example completed successfully!"
    echo ""
    echo "Generated files:"
    if [ -f "GeneratedTestServlet.java" ]; then
        echo "  - GeneratedTestServlet.java ($(wc -l < GeneratedTestServlet.java) lines)"
    fi
    
    echo ""
    echo "To run with your own JSP file:"
    echo "  java -cp \".:$CLASSPATH\" JSPCodeGeneratorExample input.jsp output.java"
    
else
    echo "✗ Compilation failed!"
    exit 1
fi
