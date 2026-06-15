#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.2
# ----------------------------------------------------------------------------

set -e

# Find the project base dir, i.e. the closest directory that contains a .mvn directory.
find_maven_basedir() {
  if [ -z "$1" ]; then
    echo "Path not specified to find_maven_basedir"
    return 1
  fi

  basedir="$1"
  wdir="$1"
  while [ "$wdir" != '/' ] ; do
    if [ -d "$wdir"/.mvn ] ; then
      basedir=$wdir
      break
    fi
    # workaround for MWRAPPER-299
    if [ "$wdir" = "$basedir" ] ; then
      break
    fi
    wdir=$(cd "$wdir/.."; pwd)
  done
  printf '%s' "$(cd "$basedir"; pwd)"
}

# concatenates all lines of a file
concat_lines() {
  if [ -f "$1" ]; then
    # Remove \r in case we run on Windows within Git Bash
    # 1. If fed in a file stream, 2. if it issues a command substitution
    tr -s '\r\n' ' ' < "$1"
  fi
}

log() {
  if [ "$MVNW_VERBOSE" = true ]; then
    printf '%s\n' "$1"
  fi
}

BASE_DIR=$(find_maven_basedir "$(dirname "$0")")
if [ -z "$BASE_DIR" ]; then
  exit 1;
fi

MAVEN_PROJECTBASEDIR=${MAVEN_BASEDIR:-"$BASE_DIR"}
log "$MAVEN_PROJECTBASEDIR"

##########################################################################################
# Extension to allow automatically downloading the maven-wrapper.jar from Maven-central
# This allows using the maven wrapper in projects that prohibit checking in binary data.
##########################################################################################
wrapperJarPath="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
if [ -r "$wrapperJarPath" ]; then
    log "Found $wrapperJarPath"
else
    log "Couldn't find $wrapperJarPath, downloading it ..."

    if [ -n "$MVNW_REPOURL" ]; then
      wrapperUrl="$MVNW_REPOURL/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
    else
      wrapperUrl="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
    fi
    while IFS="=" read -r key value; do
      # Remove '\r' from value to support Windows
      value=$(echo "$value" | tr -d '\r')
      case "$key" in (wrapperUrl) wrapperUrl="$value"; break ;;
      esac
    done < "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"
    log "Downloading from: $wrapperUrl"

    if $MVNW_VERBOSE; then
      wgetVerbosity=""
      curlVerbosity=""
    else
      wgetVerbosity="-q"
      curlVerbosity="-s"
    fi
    if command -v wget > /dev/null; then
        log "Found wget ... using wget"
        [ -z "$MVNW_USERNAME" ] || [ -z "$MVNW_PASSWORD" ] || wgetVerbosity="$wgetVerbosity --http-user="$MVNW_USERNAME" --http-password="$MVNW_PASSWORD""
        wget $wgetVerbosity "$wrapperUrl" -O "$wrapperJarPath" ||
            rm -f "$wrapperJarPath"
    elif command -v curl > /dev/null; then
        log "Found curl ... using curl"
        [ -z "$MVNW_USERNAME" ] || [ -z "$MVNW_PASSWORD" ] || curlVerbosity="$curlVerbosity -u $MVNW_USERNAME:$MVNW_PASSWORD"
        curl $curlVerbosity -o "$wrapperJarPath" "$wrapperUrl" -f ||
            rm -f "$wrapperJarPath"
    else
        log "Falling back to using Java to download"
        javaSource="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/MavenWrapperDownloader.java"
        javaClass="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/MavenWrapperDownloader"
        # For compatibility with shebang-less mvn, see:
        if [ -e "$javaSource" ]; then
            if [ ! -e "$javaClass.class" ]; then
                log " - Compiling MavenWrapperDownloader.java ..."
                ("$JAVA_HOME/bin/javac" "$javaSource")
            fi
            if [ -e "$javaClass.class" ]; then
                log " - Running MavenWrapperDownloader.java ..."
                ("$JAVA_HOME/bin/java" -cp .mvn/wrapper MavenWrapperDownloader "$wrapperUrl" "$wrapperJarPath") ||
                    rm -f "$wrapperJarPath"
            fi
        fi
    fi
fi
##########################################################################################
# End of extension
##########################################################################################

# If specified, validate the SHA-256 sum of the Maven wrapper jar file
wrapperSha256Sum=""
while IFS="=" read -r key value; do
  # Remove '\r' from value to support Windows
  value=$(echo "$value" | tr -d '\r')
  case "$key" in (wrapperSha256Sum) wrapperSha256Sum=$value; break ;;
  esac
done < "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties"
if [ -n "$wrapperSha256Sum" ]; then
  wrapperSha256Result=false
  if command -v sha256sum > /dev/null; then
    if echo "$wrapperSha256Sum  $wrapperJarPath" | sha256sum -c > /dev/null 2>&1; then
      wrapperSha256Result=true
    fi
  elif command -v shasum > /dev/null; then
    if echo "$wrapperSha256Sum  $wrapperJarPath" | shasum -a 256 -c > /dev/null 2>&1; then
      wrapperSha256Result=true
    fi
  else
    echo "Checksum validation was requested but neither 'sha256sum' nor 'shasum' are available."
    echo "Please install one of these tools or disable validation by removing 'wrapperSha256Sum' from your maven-wrapper.properties."
    exit 1
  fi
  if [ $wrapperSha256Result = false ]; then
    echo "Error: Failed to validate Maven wrapper SHA-256, your Maven wrapper installation is corrupt."
    echo "Please delete .maven/wrapper/maven-wrapper.jar and download again using the maven wrapper."
    exit 1
  fi
fi

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
        echo ""
        echo "Please set the JAVA_HOME variable in your environment to match the"
        echo "location of your Java installation." >&2
        exit 1
    fi
else
    JAVACMD="$(\unset -f command 2>/dev/null; \command -v java)" || :
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
        echo ""
        echo "Please set the JAVA_HOME variable in your environment to match the"
        echo "location of your Java installation." >&2
        exit 1
    fi
fi

MAVEN_OPTS="$(concat_lines "$MAVEN_PROJECTBASEDIR/.mvn/jvm.config") $MAVEN_OPTS"

# shellcheck disable=SC2086 # safe args
exec "$JAVACMD" \
  $MAVEN_OPTS \
  $MAVEN_DEBUG_OPTS \
  -classpath "$wrapperJarPath" \
  "-Dmaven.multiModuleProjectDirectory=${MAVEN_PROJECTBASEDIR}" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
