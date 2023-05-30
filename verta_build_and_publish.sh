#!/bin/bash

set -eo pipefail

export JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF8
if [ -z "$BRANCH_NAME" ]; then
    BRANCH_NAME="$(git rev-parse --abbrev-ref HEAD)"
fi
export VERSION_SUFFIX=$(echo $BRANCH_NAME | sed 's,/,-,g' | tr '[:upper:]' '[:lower:]')
export PROJECT_REVISION=$(mvn help:evaluate -Dexpression=revision -q -DforceStdout)
export POM=$1
if [ -z "$POM" ]; then
    echo "Using default pom.xml"
    POM="pom.xml"
else
    echo "Using pom file: $POM"
fi

if [ -z "$PARALLELISM" ]
then
    echo "Disabling parallelism"
else
    echo "Using parallelism: $PARALLELISM"
fi

# require the revision to end in -SNAPSHOT
if [ "$PROJECT_REVISION" == "${PROJECT_REVISION/%-SNAPSHOT/}" ]; then
    echo The "revision" property in pom.xml must end with "-SNAPSHOT" for this script to work correctly.
    echo Actual value: revision = $PROJECT_REVISION
    exit 1
fi

# Insert branch name in project version
export PROJECT_VERSION=${PROJECT_REVISION/%-SNAPSHOT/-${VERSION_SUFFIX}-SNAPSHOT}

# When building verta main replace -SNAPSHOT with commit info
if [ "$BRANCH_NAME" == "verta/main" ]; then
    COMMIT_INFO="$(TZ=UTC git show -s --format=%cd--%h --date='format-local:%Y-%m-%dT%H-%M-%S' --abbrev=7)"
    export PROJECT_VERSION=${PROJECT_VERSION/%-SNAPSHOT/-$COMMIT_INFO}
fi

# report project version to github workflow
GITHUB_OUTPUT=${GITHUB_OUTPUT:-/dev/null}
echo "project_version=$PROJECT_VERSION" >> $GITHUB_OUTPUT

export MAVEN_PARAMS="$PARALLELISM -Pdist-hadoop3,hadoop3,bundle-contrib-exts -Dpmd.skip=true -Denforcer.skip -Dforbiddenapis.skip=true -Dcheckstyle.skip=true -Danimal.sniffer.skip=true -Djacoco.skip=true -DskipTests -f $POM"
mvn -B versions:set -DnewVersion=$PROJECT_VERSION > /dev/null
mvn -B source:jar deploy $MAVEN_PARAMS || {
    mvn -B versions:set -DnewVersion=$PROJECT_REVISION > /dev/null
    exit 1
}

mvn -B versions:set -DnewVersion=$PROJECT_REVISION > /dev/null
