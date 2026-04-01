#!/bin/sh
##############################################################################
# Gradle start up script for UN*X
##############################################################################
APP_HOME="`pwd -P`"
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Use the maximum available memory when running on CI
if [ -n "$CI" ]; then
    DEFAULT_JVM_OPTS='"-Xmx512m" "-Xms64m"'
fi

exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
