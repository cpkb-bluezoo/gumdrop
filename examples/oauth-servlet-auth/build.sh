#!/bin/bash
#
# Build script for OAuth servlet authentication example
# 
# This script compiles the OAuth authentication example and packages it
# for deployment to a Gumdrop servlet container.
#
# Prerequisites:
# - gumdrop.jar in classpath or GUMDROP_HOME/dist/
# - servlet-api.jar (typically from lib/ directory)
# - jsonparser.jar (from cpkb-bluezoo/jsonparser/dist/)

set -e  # Exit on any error

# Configuration
EXAMPLE_NAME="oauth-servlet-auth"
SRC_DIR="src/main/java"
BUILD_DIR="build"
DIST_DIR="dist"

# Find required JAR files
if [ -n "$GUMDROP_HOME" ]; then
    GUMDROP_JAR="$GUMDROP_HOME/dist/gumdrop.jar"
    SERVLET_API_JAR="$GUMDROP_HOME/lib/javax.servlet-api-4.0.1.jar"
else
    GUMDROP_JAR="../../dist/gumdrop.jar"
    SERVLET_API_JAR="../../lib/javax.servlet-api-4.0.1.jar"
fi

# Look for jsonparser.jar in several locations
JSONPARSER_JAR=""
JSONPARSER_LOCATIONS=(
    "~/cpkb-bluezoo/jsonparser/dist/jsonparser-*.jar"
    "lib/jsonparser.jar"
    "../../../jsonparser/dist/jsonparser-*.jar"
)

for location in "${JSONPARSER_LOCATIONS[@]}"; do
    # Expand tilde and glob
    expanded=$(eval echo $location)
    if [ -f $expanded ]; then
        JSONPARSER_JAR=$expanded
        break
    fi
done

if [ -z "$JSONPARSER_JAR" ]; then
    echo "Error: Could not find jsonparser.jar"
    echo "Please ensure jsonparser.jar is available in one of these locations:"
    for location in "${JSONPARSER_LOCATIONS[@]}"; do
        echo "  - $location"
    done
    exit 1
fi

# Verify all required JARs exist
for jar in "$GUMDROP_JAR" "$SERVLET_API_JAR" "$JSONPARSER_JAR"; do
    if [ ! -f "$jar" ]; then
        echo "Error: Required JAR file not found: $jar"
        exit 1
    fi
done

echo "Building $EXAMPLE_NAME..."
echo "Using JARs:"
echo "  - Gumdrop: $GUMDROP_JAR"
echo "  - Servlet API: $SERVLET_API_JAR" 
echo "  - JSON Parser: $JSONPARSER_JAR"

# Create build directories
mkdir -p "$BUILD_DIR"
mkdir -p "$DIST_DIR"

# Compile Java sources
echo "Compiling Java sources..."
CLASSPATH="$GUMDROP_JAR:$SERVLET_API_JAR:$JSONPARSER_JAR"

javac -d "$BUILD_DIR" \
      -cp "$CLASSPATH" \
      -Xlint:deprecation \
      -Xlint:unchecked \
      $(find "$SRC_DIR" -name "*.java")

if [ $? -ne 0 ]; then
    echo "Error: Compilation failed"
    exit 1
fi

# Copy configuration files
echo "Copying configuration files..."
cp oauth-config.properties "$BUILD_DIR/"
cp web.xml "$BUILD_DIR/"

# Create WAR file structure
WAR_DIR="$BUILD_DIR/war"
mkdir -p "$WAR_DIR/WEB-INF/classes"
mkdir -p "$WAR_DIR/WEB-INF/lib"

# Copy compiled classes
cp -r "$BUILD_DIR/com" "$WAR_DIR/WEB-INF/classes/"
cp oauth-config.properties "$WAR_DIR/WEB-INF/classes/"

# Copy web.xml
cp web.xml "$WAR_DIR/WEB-INF/"

# Copy required JARs (jsonparser is needed at runtime)
cp "$JSONPARSER_JAR" "$WAR_DIR/WEB-INF/lib/"

# Create error pages directory
mkdir -p "$WAR_DIR/error"
cat > "$WAR_DIR/error/unauthorized.html" << 'EOF'
<!DOCTYPE html>
<html>
<head>
    <title>401 Unauthorized</title>
</head>
<body>
    <h1>401 Unauthorized</h1>
    <p>Authentication is required to access this resource.</p>
    <p>Please provide a valid OAuth 2.0 Bearer token in the Authorization header.</p>
</body>
</html>
EOF

cat > "$WAR_DIR/error/forbidden.html" << 'EOF'
<!DOCTYPE html>
<html>
<head>
    <title>403 Forbidden</title>
</head>
<body>
    <h1>403 Forbidden</h1>
    <p>You do not have permission to access this resource.</p>
    <p>Required role: admin</p>
</body>
</html>
EOF

# Create WAR file
echo "Creating WAR file..."
cd "$WAR_DIR"
jar cf "../../$DIST_DIR/$EXAMPLE_NAME.war" *
cd - > /dev/null

# Create distribution package
echo "Creating distribution package..."
cd "$DIST_DIR"
tar czf "$EXAMPLE_NAME.tar.gz" \
    "$EXAMPLE_NAME.war" \
    ../README.md \
    ../oauth-config.properties \
    ../build.sh

echo "Build completed successfully!"
echo "Output files:"
echo "  - WAR file: $DIST_DIR/$EXAMPLE_NAME.war"
echo "  - Package: $DIST_DIR/$EXAMPLE_NAME.tar.gz"
echo ""
echo "To deploy:"
echo "  1. Copy $EXAMPLE_NAME.war to your Gumdrop webapps directory"
echo "  2. Configure oauth-config.properties with your OAuth server details"
echo "  3. Restart Gumdrop server"
