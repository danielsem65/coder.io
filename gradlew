#!/bin/sh
# Gradle wrapper startup script - delegates to gradle-wrapper.jar
# If the jar is missing, downloads it automatically

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# Extract download URL from properties
if [ -f "$PROPS" ]; then
    WRAPPER_URL=$(grep 'distributionUrl' "$PROPS" | sed 's/.*=//' | sed 's/\\:/:/g')
fi

# Download gradle-wrapper.jar if missing
if [ ! -f "$JAR" ]; then
    echo "Downloading gradle-wrapper.jar..."
    mkdir -p "$(dirname "$JAR")"
    # Try curl first, then wget
    if command -v curl >/dev/null 2>&1; then
        curl -sL -o "$JAR" "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$JAR" "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
    else
        echo "ERROR: gradle-wrapper.jar not found and no download tool available."
        echo "Install curl or wget, or copy gradle-wrapper.jar from any Gradle project."
        exit 1
    fi
fi

# Determine Java
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1; then
    echo "ERROR: Java not found. Please install JDK 17+ and set JAVA_HOME."
    exit 1
fi

exec "$JAVACMD" \
    -Xmx256m \
    -Dorg.gradle.appname="gradlew" \
    -classpath "$JAR" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
