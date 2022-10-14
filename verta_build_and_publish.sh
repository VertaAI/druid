#!/bin/bash

set -eo pipefail

export JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF8
export VERSION_SUFFIX="$(git rev-parse --abbrev-ref HEAD)"
export VERSION_SUFFIX=$(echo $VERSION_SUFFIX | sed 's,/,-,g')
export PROJECT_VERSION=$(mvn help:evaluate -Dexpression=revision -q -DforceStdout)
export PROJECT_VERSION=$(echo $PROJECT_VERSION | sed "s,-SNAPSHOT,-${VERSION_SUFFIX}-SNAPSHOT,g")
export MAVEN_PARAMS='-Pdist-hadoop3,hadoop3,bundle-contrib-exts -Dpmd.skip=true -Denforcer.skip -Dforbiddenapis.skip=true -Dcheckstyle.skip=true -Danimal.sniffer.skip=true -Djacoco.skip=true -DskipTests'
mvn -B deploy -Drevision=$PROJECT_VERSION $MAVEN_PARAMS
