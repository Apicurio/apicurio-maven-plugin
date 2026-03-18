#!/bin/bash

# =============================================================================
# verify-artifact-dependencies.sh
#
# Creates a temporary Maven project with a single dependency and runs the
# verify-project-dependencies goal to validate that all transitive dependencies
# are productized.
# =============================================================================

set -e

# ---- Defaults ----
ARTIFACT=""
REPOSITORIES=""
INSECURE="true"
IGNORE_GAVS=""
VERBOSE="true"
CSV=""
WORK_DIR=""
CLEANUP="true"
PLUGIN_VERSION="0.0.8"

# ---- Parse command-line arguments ----
while [[ $# -gt 0 ]]; do
    case "$1" in
        --artifact)
            ARTIFACT="$2"
            shift 2
            ;;
        --repositories)
            REPOSITORIES="$2"
            shift 2
            ;;
        --insecure)
            INSECURE="$2"
            shift 2
            ;;
        --ignoreGAVs)
            IGNORE_GAVS="$2"
            shift 2
            ;;
        --verbose)
            VERBOSE="$2"
            shift 2
            ;;
        --csv)
            CSV="$2"
            shift 2
            ;;
        --workDir)
            WORK_DIR="$2"
            shift 2
            ;;
        --cleanup)
            CLEANUP="$2"
            shift 2
            ;;
        --plugin-version)
            PLUGIN_VERSION="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  --artifact GAV          Artifact to verify (groupId:artifactId:version) [required]"
            echo "  --repositories URLS     Comma-separated remote repository URLs [prompted if omitted]"
            echo "  --insecure BOOL         Disable SSL certificate verification (default: true)"
            echo "  --ignoreGAVs PATTERNS   Comma-separated groupId:artifactId patterns to skip"
            echo "  --verbose BOOL          Enable verbose output (default: true)"
            echo "  --csv PATH              Path to CSV output file"
            echo "  --workDir PATH          Working directory for the generated project [prompted if omitted]"
            echo "  --cleanup BOOL          Delete working directory after completion (default: true)"
            echo "  --plugin-version VER    Version of apicurio-maven-plugin to use (default: ${PLUGIN_VERSION})"
            echo "  --help, -h              Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information."
            exit 1
            ;;
    esac
done

# ---- Prompt for artifact if not provided ----
if [[ -z "$ARTIFACT" ]]; then
    read -rp "Enter artifact GAV (groupId:artifactId:version): " ARTIFACT
    if [[ -z "$ARTIFACT" ]]; then
        echo "Error: artifact GAV is required."
        exit 1
    fi
fi

# Validate artifact format
IFS=':' read -ra GAV_PARTS <<< "$ARTIFACT"
if [[ ${#GAV_PARTS[@]} -lt 3 || ${#GAV_PARTS[@]} -gt 4 ]]; then
    echo "Error: Invalid artifact format '${ARTIFACT}'."
    echo "Expected: groupId:artifactId:version or groupId:artifactId:version:packaging"
    exit 1
fi

DEP_GROUP_ID="${GAV_PARTS[0]}"
DEP_ARTIFACT_ID="${GAV_PARTS[1]}"
DEP_VERSION="${GAV_PARTS[2]}"
DEP_TYPE="${GAV_PARTS[3]:-jar}"

echo ""
echo "Artifact:  ${DEP_GROUP_ID}:${DEP_ARTIFACT_ID}:${DEP_VERSION} (${DEP_TYPE})"

# ---- Prompt for repositories if not provided ----
if [[ -z "$REPOSITORIES" ]]; then
    echo ""
    echo "Select a remote repository:"
    echo "  1) MRRC  - https://maven.repository.redhat.com/ga"
    echo "  2) Indy  - https://indy.corp.redhat.com/api/content/maven/group/static"
    echo "  3) Other - enter a custom URL"
    echo ""
    read -rp "Choice [1/2/3]: " REPO_CHOICE

    case "$REPO_CHOICE" in
        1)
            REPOSITORIES="https://maven.repository.redhat.com/ga"
            ;;
        2)
            REPOSITORIES="https://indy.corp.redhat.com/api/content/maven/group/static"
            ;;
        3)
            read -rp "Enter remote repository URL(s) (comma-separated): " REPOSITORIES
            if [[ -z "$REPOSITORIES" ]]; then
                echo "Error: at least one remote repository URL is required."
                exit 1
            fi
            ;;
        *)
            echo "Error: invalid choice '${REPO_CHOICE}'."
            exit 1
            ;;
    esac
fi

echo "Repos:     ${REPOSITORIES}"
echo "Insecure:  ${INSECURE}"
echo "Verbose:   ${VERBOSE}"
[[ -n "$IGNORE_GAVS" ]] && echo "Ignoring:  ${IGNORE_GAVS}"
[[ -n "$CSV" ]] && echo "CSV:       ${CSV}"

# ---- Determine working directory ----
if [[ -z "$WORK_DIR" ]]; then
    echo ""
    echo "Select a working directory:"
    echo "  1) ./target/work"
    echo "  2) Random temporary directory (e.g. /tmp/verify-artifact-XXXXXXXXXX)"
    echo "  3) Custom - enter a path"
    echo ""
    read -rp "Choice [1/2/3]: " DIR_CHOICE

    case "$DIR_CHOICE" in
        1)
            WORK_DIR="./target/work"
            ;;
        2)
            WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/verify-artifact-XXXXXXXXXX")
            ;;
        3)
            read -rp "Enter working directory path: " WORK_DIR
            if [[ -z "$WORK_DIR" ]]; then
                echo "Error: working directory path is required."
                exit 1
            fi
            ;;
        *)
            echo "Error: invalid choice '${DIR_CHOICE}'."
            exit 1
            ;;
    esac
fi
mkdir -p "$WORK_DIR"
echo "Work dir:  ${WORK_DIR}"
echo ""

# ---- Build repository XML fragments ----
REPO_XML=""
REPO_INDEX=0
IFS=',' read -ra REPO_LIST <<< "$REPOSITORIES"
for REPO_URL in "${REPO_LIST[@]}"; do
    REPO_URL=$(echo "$REPO_URL" | xargs)  # trim whitespace
    REPO_XML+="        <repository>
            <id>repo-${REPO_INDEX}</id>
            <url>${REPO_URL}</url>
        </repository>
"
    REPO_INDEX=$((REPO_INDEX + 1))
done

# ---- Build ignoreGAVs XML fragment ----
IGNORE_XML=""
if [[ -n "$IGNORE_GAVS" ]]; then
    IGNORE_XML="                        <ignoreGAVs>
"
    IFS=',' read -ra IGNORE_LIST <<< "$IGNORE_GAVS"
    for PATTERN in "${IGNORE_LIST[@]}"; do
        PATTERN=$(echo "$PATTERN" | xargs)
        IGNORE_XML+="                            <ignoreGAV>${PATTERN}</ignoreGAV>
"
    done
    IGNORE_XML+="                        </ignoreGAVs>"
fi

# ---- Build outputCsv XML fragment ----
CSV_XML=""
if [[ -n "$CSV" ]]; then
    CSV_XML="                        <outputCsv>${CSV}</outputCsv>"
fi

# ---- Generate pom.xml ----
cat > "${WORK_DIR}/pom.xml" <<POMEOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>verify-artifact-dependencies</groupId>
    <artifactId>verify</artifactId>
    <version>DEV</version>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>

    <repositories>
${REPO_XML}    </repositories>

    <dependencies>
        <dependency>
            <groupId>${DEP_GROUP_ID}</groupId>
            <artifactId>${DEP_ARTIFACT_ID}</artifactId>
            <version>${DEP_VERSION}</version>
            <type>${DEP_TYPE}</type>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>io.apicurio</groupId>
                <artifactId>apicurio-maven-plugin</artifactId>
                <version>${PLUGIN_VERSION}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>verify-project-dependencies</goal>
                        </goals>
                        <configuration>
                            <verbose>${VERBOSE}</verbose>
${IGNORE_XML}
${CSV_XML}
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

</project>
POMEOF

echo "Generated pom.xml in ${WORK_DIR}"
echo ""

# ---- Build MAVEN_OPTS ----
LOCAL_REPO="/tmp/repo${SECONDS}"
MAVEN_OPTS_EXTRA=""
if [[ "$INSECURE" == "true" ]]; then
    MAVEN_OPTS_EXTRA="-Daether.connector.https.securityMode=insecure"
fi

# ---- Run Maven ----
echo "Running Maven verification..."
echo "Local repo: ${LOCAL_REPO}"
echo "============================================================"
echo ""

cd "$WORK_DIR"
set +e
MAVEN_OPTS="${MAVEN_OPTS_EXTRA} ${MAVEN_OPTS}" mvn clean verify \
    -Dmaven.repo.local="${LOCAL_REPO}"
MVN_EXIT=$?
set -e

# ---- Cleanup ----
if [[ "$CLEANUP" == "true" ]]; then
    echo ""
    echo "Cleaning up working directory: ${WORK_DIR}"
    rm -rf "$WORK_DIR"
fi

exit $MVN_EXIT
