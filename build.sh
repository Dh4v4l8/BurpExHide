#!/bin/bash

# BurpExHide Build Script
# Compiles the extension and packages it as a JAR

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
OUTPUT_JAR="$SCRIPT_DIR/BurpExHide.jar"

# Burp Suite API JAR path - update this if needed
# The extension uses interfaces from burp.* package which are available at runtime
# We need the Burp Extender API JAR for compilation

echo "============================================"
echo "  BurpExHide Build Script"
echo "============================================"
echo ""

# Clean previous build
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# Check for Burp API JAR
BURP_API_JAR=""

# Common locations to look for the Burp API
SEARCH_PATHS=(
    "$SCRIPT_DIR/lib/burp-extender-api.jar"
    "$SCRIPT_DIR/burp-extender-api.jar"
    "$HOME/.BurpSuite/burp-extender-api.jar"
    "$HOME/BurpSuitePro/burp-extender-api.jar"
)

for path in "${SEARCH_PATHS[@]}"; do
    if [ -f "$path" ]; then
        BURP_API_JAR="$path"
        break
    fi
done

if [ -z "$BURP_API_JAR" ]; then
    echo "[!] Burp Extender API JAR not found."
    echo "    Attempting to compile with interface stubs..."
    echo ""

    # Create minimal interface stubs for compilation
    STUB_DIR="$BUILD_DIR/stubs"
    mkdir -p "$STUB_DIR/burp"

    cat > "$STUB_DIR/burp/IBurpExtender.java" << 'STUB'
package burp;
public interface IBurpExtender {
    void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks);
}
STUB

    cat > "$STUB_DIR/burp/IBurpExtenderCallbacks.java" << 'STUB'
package burp;
import java.awt.Component;
public interface IBurpExtenderCallbacks {
    void setExtensionName(String name);
    void addSuiteTab(ITab tab);
    void registerExtensionStateListener(IExtensionStateListener listener);
    void printOutput(String message);
    void printError(String message);
}
STUB

    cat > "$STUB_DIR/burp/ITab.java" << 'STUB'
package burp;
import java.awt.Component;
public interface ITab {
    String getTabCaption();
    Component getUiComponent();
}
STUB

    cat > "$STUB_DIR/burp/IExtensionStateListener.java" << 'STUB'
package burp;
public interface IExtensionStateListener {
    void extensionUnloaded();
}
STUB

    echo "[*] Compiling interface stubs..."
    javac -d "$BUILD_DIR" "$STUB_DIR/burp/"*.java

    echo "[*] Compiling BurpExtender.java..."
    javac -cp "$BUILD_DIR" -d "$BUILD_DIR" "$SRC_DIR/burp/BurpExtender.java"
else
    echo "[*] Found Burp API JAR: $BURP_API_JAR"
    echo "[*] Compiling BurpExtender.java..."
    javac -cp "$BURP_API_JAR" -d "$BUILD_DIR" "$SRC_DIR/burp/BurpExtender.java"
fi

echo "[*] Packaging JAR..."

# Only include BurpExtender class, not stubs (Burp provides these at runtime)
cd "$BUILD_DIR"
jar cf "$OUTPUT_JAR" burp/BurpExtender.class burp/BurpExtender\$*.class 2>/dev/null || \
jar cf "$OUTPUT_JAR" burp/BurpExtender.class

cd "$SCRIPT_DIR"

# Verify
if [ -f "$OUTPUT_JAR" ]; then
    SIZE=$(du -h "$OUTPUT_JAR" | cut -f1)
    echo ""
    echo "============================================"
    echo "  Build Successful!"
    echo "  Output: $OUTPUT_JAR ($SIZE)"
    echo "============================================"
    echo ""
    echo "  To install:"
    echo "  1. Open Burp Suite"
    echo "  2. Go to Extensions > Installed > Add"
    echo "  3. Extension type: Java"
    echo "  4. Select: $OUTPUT_JAR"
    echo ""
else
    echo ""
    echo "[!] Build FAILED"
    exit 1
fi
