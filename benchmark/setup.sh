#!/usr/bin/env bash
# Copyright 2026 Gobinath Loganathan
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Downloads and builds everything the benchmark compares, at pinned versions, into .work/.
# Needs JAVA21_HOME (java-index) and JAVA25_HOME (jdtls and java-language-server need Java 25),
# git, curl and mvn.

set -euo pipefail
cd "$(dirname "$0")"
BENCH=$PWD
WORK=$BENCH/.work
: "${JAVA21_HOME:?set JAVA21_HOME to a JDK 21+}"
: "${JAVA25_HOME:?set JAVA25_HOME to a JDK 25+}"

JDTLS_VERSION=1.63.0
JDTLS_TARBALL=jdt-language-server-1.63.0-202610191322.tar.gz
JLS_COMMIT=58daaa29a0e2fe22764283607da6801cf8b493b9
PETCLINIC_COMMIT=500158f732419217507c7656904b8e6aa1bcc0d6
JACKSON_COMMIT=4d6a0a23a8640e37bcbf37bc351dbe8cb16d7003  # jackson-databind-2.23.0
HIBERNATE_TAG=7.4.15  # 4d66f592cf52210264a18697c76b3936643d86b9

mkdir -p "$WORK/servers" "$WORK/repos"

echo "== java-index"
(cd "$BENCH/.." && JAVA_HOME=$JAVA21_HOME mvn -q package -DskipTests)
if [ -n "${GRAALVM_HOME:-}" ]; then
  echo "== java-index native relay"
  (cd "$BENCH/.." && JAVA_HOME=$GRAALVM_HOME mvn -q -Pnative package -DskipTests)
else
  echo "   (set GRAALVM_HOME to also build the native relay, for the java-index-native server)"
fi

echo "== jdtls $JDTLS_VERSION"
if [ ! -d "$WORK/servers/jdtls/plugins" ]; then
  curl -fsSL -o "$WORK/servers/$JDTLS_TARBALL" \
    "https://download.eclipse.org/jdtls/milestones/$JDTLS_VERSION/$JDTLS_TARBALL"
  mkdir -p "$WORK/servers/jdtls"
  tar xzf "$WORK/servers/$JDTLS_TARBALL" -C "$WORK/servers/jdtls"
fi

echo "== java-language-server ${JLS_COMMIT:0:8}"
if [ ! -d "$WORK/servers/jls" ]; then
  git clone -q https://github.com/georgewfraser/java-language-server.git "$WORK/servers/jls"
fi
git -C "$WORK/servers/jls" checkout -q "$JLS_COMMIT"
# Its own build also jlinks a private JDK and packages a VS Code extension; the jar is enough here.
(cd "$WORK/servers/jls" && JAVA_HOME=$JAVA25_HOME mvn -q package -DskipTests)

echo "== spring-petclinic ${PETCLINIC_COMMIT:0:8}"
if [ ! -d "$WORK/repos/spring-petclinic" ]; then
  git clone -q https://github.com/spring-projects/spring-petclinic.git "$WORK/repos/spring-petclinic"
fi
git -C "$WORK/repos/spring-petclinic" checkout -q "$PETCLINIC_COMMIT"
# Download its dependencies now so no server's startup time includes network downloads.
(cd "$WORK/repos/spring-petclinic" && JAVA_HOME=$JAVA21_HOME ./mvnw -q dependency:go-offline dependency:sources)

echo "== jackson-databind ${JACKSON_COMMIT:0:8} (for bench.py --project jackson-databind)"
if [ ! -d "$WORK/repos/jackson-databind" ]; then
  git clone -q https://github.com/FasterXML/jackson-databind.git "$WORK/repos/jackson-databind"
fi
git -C "$WORK/repos/jackson-databind" checkout -q "$JACKSON_COMMIT"
(cd "$WORK/repos/jackson-databind" && JAVA_HOME=$JAVA21_HOME ./mvnw -q dependency:go-offline dependency:sources)

echo "== hibernate-orm $HIBERNATE_TAG (for bench.py --project hibernate-orm)"
if [ ! -d "$WORK/repos/hibernate-orm" ]; then
  git clone -q --depth 1 --branch "$HIBERNATE_TAG" https://github.com/hibernate/hibernate-orm.git "$WORK/repos/hibernate-orm"
fi
# Build once (its build needs JDK 25): downloads the dependencies and generates the HQL parser
# sources, as in a checkout someone has already built.
(cd "$WORK/repos/hibernate-orm" && JAVA_HOME=$JAVA25_HOME ./gradlew -q testClasses)

echo "ready: python3 bench.py"
