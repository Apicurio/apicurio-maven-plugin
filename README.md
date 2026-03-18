## Apicurio Maven Plugin

A collection of Maven plugin goals used by Apicurio projects, primarily for validating that
dependencies in productized (Red Hat) builds are properly aligned.

### Goals Overview

| Goal | Description |
|------|-------------|
| `merge` | Merges multiple Java `.properties` files into a single output file. |
| `verify-dependencies` | Validates that files (JARs, etc.) in directories or zip distributions are productized. |
| `verify-maven-repository` | Validates an offline Maven repository by resolving full dependency trees. |
| `verify-project-dependencies` | Validates that all dependencies of the current Maven project are productized. |
| `verify-artifact-dependencies` | Validates that all dependencies of a single Maven artifact are productized. |

---

### `merge` — Merge Properties Files

Merges multiple Java `.properties` files into a single output file. Optionally deletes the
input files after merging.

#### Configuration

```xml
<plugin>
    <groupId>io.apicurio</groupId>
    <artifactId>apicurio-maven-plugin</artifactId>
    <version>${apicurio-maven-plugin.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>merge</goal>
            </goals>
            <configuration>
                <output>${project.build.directory}/merged.properties</output>
                <inputs>
                    <input>${project.basedir}/src/main/resources/a.properties</input>
                    <input>${project.basedir}/src/main/resources/b.properties</input>
                </inputs>
                <deleteInputs>false</deleteInputs>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `output` | Yes | — | Path to the merged output properties file. |
| `inputs` | Yes | — | List of input properties files to merge. |
| `deleteInputs` | No | `false` | Delete input files after merging. |

---

### `verify-dependencies` — Verify Files are Productized

Scans directories and/or zip distributions for dependency files (e.g. JARs) and validates
that their file names contain a productization suffix (`-redhat-` or `.redhat-`). This is a
simple file-name-based check — it does not resolve Maven dependency trees.

#### Configuration

```xml
<plugin>
    <groupId>io.apicurio</groupId>
    <artifactId>apicurio-maven-plugin</artifactId>
    <version>${apicurio-maven-plugin.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>verify-dependencies</goal>
            </goals>
            <configuration>
                <fileTypes>
                    <fileType>jar</fileType>
                </fileTypes>
                <directories>
                    <directory>${project.build.directory}/lib</directory>
                </directories>
                <distributions>
                    <distribution>${project.build.directory}/my-app.zip</distribution>
                </distributions>
                <ignoreFiles>
                    <ignoreFile>**/my-internal-lib-*.jar</ignoreFile>
                </ignoreFiles>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `fileTypes` | No | `jar` | List of file extensions to check. |
| `directories` | No | — | List of directories to scan for dependency files. |
| `distributions` | No | — | List of zip files to scan for dependency files. |
| `ignoreFiles` | No | — | List of wildcard patterns for files to ignore. |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |

---

### `verify-maven-repository` — Verify an Offline Maven Repository

Validates an offline Maven repository (directory or `.zip` file) by resolving the full
transitive dependency tree for each matching artifact using Maven's Aether resolver. Checks
that all compile and runtime scoped dependencies are productized. This is useful for
validating PNC-produced offline Maven repositories before delivery.

#### Command Line Usage

```bash
mvn io.apicurio:apicurio-maven-plugin:verify-maven-repository \
    -DrepositoryZip=/path/to/maven-repository.zip \
    -DartifactIncludes=apicurio-* \
    -Dverify.verbose=true
```

With a repository directory instead of a zip:

```bash
mvn io.apicurio:apicurio-maven-plugin:verify-maven-repository \
    -DrepositoryDirectory=/path/to/maven-repository \
    -DartifactIncludes=apicurio-*
```

With excludes, ignores, and CSV output:

```bash
mvn io.apicurio:apicurio-maven-plugin:verify-maven-repository \
    -DrepositoryZip=/path/to/maven-repository.zip \
    -DartifactIncludes=apicurio-* \
    -DartifactExcludes=apicurio-registry-utils-tests,*-parent \
    -DignoreGAVs=org.example:* \
    -DoutputCsv=/tmp/unproductized.csv
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `repositoryDirectory` | No* | — | Path to an offline Maven repository directory. |
| `repositoryZip` | No* | — | Path to a `.zip` file containing an offline Maven repository. |
| `artifactIncludes` | Yes | — | Comma-separated wildcard patterns to match artifact IDs to validate (e.g. `apicurio-*`). |
| `artifactExcludes` | No | — | Comma-separated wildcard patterns to exclude artifact IDs from validation. |
| `ignoreGAVs` | No | — | Comma-separated `groupId:artifactId` patterns for dependencies to skip (e.g. `io.apicurio:*`). |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |
| `outputCsv` | No | — | Path to write a CSV file of unproductized dependencies. |

*Exactly one of `repositoryDirectory` or `repositoryZip` must be configured.

---

### `verify-project-dependencies` — Verify Current Project Dependencies

Validates that all compile and runtime scoped transitive dependencies of the current Maven
project are productized. This mojo is designed to be bound to the `verify` phase and
activated via a Maven profile during productized builds.

#### Configuration

```xml
<profile>
    <id>productized</id>
    <build>
        <plugins>
            <plugin>
                <groupId>io.apicurio</groupId>
                <artifactId>apicurio-maven-plugin</artifactId>
                <version>${apicurio-maven-plugin.version}</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>verify-project-dependencies</goal>
                        </goals>
                        <configuration>
                            <ignoreGAVs>
                                <ignoreGAV>org.example:*</ignoreGAV>
                            </ignoreGAVs>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

Then run with: `mvn verify -Pproductized`

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `ignoreGAVs` | No | — | List of `groupId:artifactId` patterns for dependencies to skip. |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |
| `outputCsv` | No | — | Path to write a CSV file of unproductized dependencies. |

---

### `verify-artifact-dependencies` — Verify a Single Artifact's Dependencies

Validates that all transitive dependencies of a single Maven artifact are productized. This
mojo does not require a Maven project and can be run directly from the command line. It
supports configurable remote and local repositories and can optionally bypass SSL certificate
verification.

#### Command Line Usage

Basic usage with Maven Central:

```bash
mvn io.apicurio:apicurio-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apicurio:apicurio-registry-app:3.0.0-redhat-00001 \
    -DremoteRepositories=https://repo1.maven.org/maven2
```

With multiple remote repositories (using `id::url` format):

```bash
mvn io.apicurio:apicurio-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apicurio:apicurio-registry-app:3.0.0-redhat-00001 \
    -DremoteRepositories=central::https://repo1.maven.org/maven2,jboss::https://repository.jboss.org/nexus/content/groups/public
```

With a local/offline repository (bypasses the `~/.m2` cache):

```bash
mvn io.apicurio:apicurio-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apicurio:apicurio-registry-app:3.0.0-redhat-00001 \
    -DlocalRepository=/path/to/offline/maven-repository \
    -Dverify.verbose=true
```

With SSL verification disabled (for repos with self-signed certificates):

```bash
mvn io.apicurio:apicurio-maven-plugin:verify-artifact-dependencies \
    -Dartifact=io.apicurio:apicurio-registry-app:3.0.0-redhat-00001 \
    -DremoteRepositories=https://my-internal-repo.example.com/maven2 \
    -Dinsecure=true
```

Exemplar when running the tool to validate a dependency against Indy:

```bash
rm -rf /tmp/empty-repo
mvn io.apicurio:apicurio-maven-plugin:0.0.8:verify-artifact-dependencies \
    -Dartifact=io.apicurio:apicurio-registry-schema-resolver:3.1.6.redhat-00011 \
    -DremoteRepositories=https://indy.corp.redhat.com/api/content/maven/group/static \
    -DlocalRepository=/tmp/empty-repo \
    -Dinsecure=true \
    -Dverify.verbose=true
```

#### Parameters

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `artifact` | Yes | — | Artifact coordinates: `groupId:artifactId:version` or `groupId:artifactId:version:packaging`. |
| `remoteRepositories` | No | — | Comma-separated remote repository URLs. Use `id::url` format to specify an ID. |
| `localRepository` | No | — | Path to a local Maven repository directory (overrides `~/.m2/repository`). |
| `insecure` | No | `false` | Disable SSL certificate verification (`-Dinsecure=true`). |
| `ignoreGAVs` | No | — | Comma-separated `groupId:artifactId` patterns for dependencies to skip. |
| `verbose` | No | `false` | Enable verbose output (`-Dverify.verbose=true`). |
| `outputCsv` | No | — | Path to write a CSV file of unproductized dependencies. |

---

### Common Concepts

#### Productization Check

All verification goals check for Red Hat productization by looking for `-redhat-` or
`.redhat-` in artifact versions. For example:
- `3.0.0-redhat-00001` — productized
- `2.15.0.redhat-00001` — productized
- `3.0.0.Final` — **not** productized
- `1.0.0-SNAPSHOT` — **not** productized

#### CSV Output

The `verify-maven-repository`, `verify-project-dependencies`, and
`verify-artifact-dependencies` goals support writing a CSV file of all unproductized
dependencies. The CSV has three columns (`groupId`, `artifactId`, `version`) and is
deduplicated and sorted.

#### Verbose Mode

All verification goals support verbose output via `-Dverify.verbose=true`. When enabled,
the goals log detailed information about dependency tree resolution, artifact matching, and
validation results.
