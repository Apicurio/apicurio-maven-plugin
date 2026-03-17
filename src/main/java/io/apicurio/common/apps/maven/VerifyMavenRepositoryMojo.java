package io.apicurio.common.apps.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.SelectorUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Validates an offline Maven repository by resolving the full transitive dependency tree of each
 * matching artifact and checking that all compile/runtime dependencies are productized (have a
 * {@code -redhat-} or {@code .redhat-} version suffix).
 *
 * <p>For each artifact matching the configured include patterns, this mojo uses Maven's dependency
 * resolver (Aether) to build the complete transitive dependency tree — exactly as Maven would
 * resolve it during a real build. This means all scope transitivity rules, optional dependency
 * exclusion, BOM imports, property resolution, and conflict resolution are handled correctly by
 * Maven itself.</p>
 *
 * <p>This mojo can operate against either a repository directory or a repository .zip file. When
 * using a .zip file, the archive may optionally contain an extra root directory wrapping the
 * repository contents.</p>
 */
@Mojo(name = "verify-maven-repository")
public class VerifyMavenRepositoryMojo extends AbstractVerifyMojo {

    /**
     * Path to an offline Maven repository directory to validate.
     * Either this or {@code repositoryZip} must be configured, but not both.
     */
    @Parameter(property = "repositoryDirectory")
    File repositoryDirectory;

    /**
     * Path to a .zip file containing an offline Maven repository to validate.
     * The zip may optionally have an extra root directory wrapping the repository contents.
     * Either this or {@code repositoryDirectory} must be configured, but not both.
     */
    @Parameter(property = "repositoryZip")
    File repositoryZip;

    /**
     * List of patterns to match artifact IDs to validate (e.g. {@code apicurio-*}).
     * Only artifacts whose artifactId matches at least one of these patterns will have their
     * full transitive dependency tree resolved and validated. Supports wildcard patterns.
     */
    @Parameter(property = "artifactIncludes", required = true)
    List<String> artifactIncludes;

    /**
     * List of patterns to exclude artifact IDs from validation (e.g. {@code *-parent}).
     * Takes precedence over {@code artifactIncludes}. Supports wildcard patterns.
     */
    @Parameter(property = "artifactExcludes")
    List<String> artifactExcludes;

    @SuppressWarnings("deprecation")
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * Coordinates of an artifact found in the repository.
     */
    record ArtifactCoordinates(String groupId, String artifactId, String version,
            String packaging) {

        /**
         * Returns the GAV string representation.
         *
         * @return the GAV string in the format "groupId:artifactId:version"
         */
        String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path tempExtractDir = null;
        Path tempLocalRepo = null;

        try {
            validateConfiguration();

            if (artifactExcludes == null) {
                artifactExcludes = List.of();
            }
            if (ignoreGAVs == null) {
                ignoreGAVs = List.of();
            }

            // Phase 1: Get the repository directory (extract zip if needed).
            File repoDir;
            if (repositoryDirectory != null) {
                repoDir = repositoryDirectory;
                getLog().info("Using repository directory: " + repoDir.getAbsolutePath());
            } else {
                getLog().info("Extracting repository zip: " + repositoryZip.getAbsolutePath());
                tempExtractDir = Files.createTempDirectory("verify-repo-extract-");
                extractZip(repositoryZip, tempExtractDir);
                repoDir = detectRepoRoot(tempExtractDir).toFile();
                getLog().info("Repository root: " + repoDir.getAbsolutePath());
            }

            // Phase 2: Scan for all POM files and find matching artifacts.
            List<ArtifactCoordinates> allArtifacts = scanRepository(repoDir);
            getLog().info("Found " + allArtifacts.size() + " total artifacts in repository.");

            List<ArtifactCoordinates> matchingArtifacts = allArtifacts.stream()
                    .filter(this::matchesIncludes)
                    .filter(a -> !matchesExcludes(a))
                    .toList();

            getLog().info("Found " + matchingArtifacts.size()
                    + " artifacts matching include patterns.");

            if (matchingArtifacts.isEmpty()) {
                throw new MojoFailureException(
                        "No artifacts matched the configured include patterns: "
                                + artifactIncludes);
            }

            // Phase 3: Set up Aether with the offline repo as the sole remote repository
            // and a clean temp local repo so nothing is pre-cached.
            tempLocalRepo = Files.createTempDirectory("verify-repo-local-");
            RemoteRepository offlineRepo = new RemoteRepository.Builder(
                    "offline-repo", "default", repoDir.toURI().toString()).build();

            DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(
                    repoSession);
            session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(
                    session, new LocalRepository(tempLocalRepo.toFile())));

            // Phase 4: For each matching artifact, resolve the dependency tree and validate.
            Set<String> unproductizedDependencies = new TreeSet<>();
            Set<String> unproductizedGavs = new TreeSet<>();
            Set<String> resolutionErrors = new TreeSet<>();
            int artifactsChecked = 0;

            for (ArtifactCoordinates coords : matchingArtifacts) {
                artifactsChecked++;
                logVerbose("Resolving dependency tree for: " + coords.gav());

                CollectResult result = collectDependencyTree(session, offlineRepo, coords);

                if (result == null) {
                    resolutionErrors.add(coords.gav()
                            + " (failed to collect dependency tree)");
                    continue;
                }

                // Report any collection exceptions as resolution errors.
                if (!result.getExceptions().isEmpty()) {
                    for (Exception ex : result.getExceptions()) {
                        resolutionErrors.add(coords.gav() + ": " + ex.getMessage());
                    }
                }

                // Walk the dependency tree and check each dependency's version.
                if (result.getRoot() != null) {
                    logVerboseDependencyTree(result.getRoot());
                    validateDependencyTree(result.getRoot(), coords.gav(),
                            unproductizedDependencies, unproductizedGavs);
                }
            }

            // Phase 5: Write CSV report (before reportResults, which may throw).
            writeCsvReport(unproductizedGavs);

            // Phase 6: Report results.
            getLog().info("=== Maven Repository Verification Results ===");
            getLog().info("  Artifacts checked:          " + artifactsChecked);
            reportResults(unproductizedDependencies, resolutionErrors);

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Error verifying maven repository", e);
        } finally {
            cleanupTempDir(tempExtractDir);
            cleanupTempDir(tempLocalRepo);
        }
    }

    /**
     * Collects the transitive dependency tree for an artifact using Maven's Aether resolver.
     *
     * @param session the repository session configured with a temp local repo
     * @param offlineRepo the remote repository pointing to the offline Maven repo
     * @param coords the artifact coordinates to resolve
     * @return the collect result containing the dependency tree, or null on complete failure
     */
    private CollectResult collectDependencyTree(RepositorySystemSession session,
            RemoteRepository offlineRepo, ArtifactCoordinates coords) {
        try {
            String extension = "pom".equals(coords.packaging()) ? "pom" : "jar";
            CollectRequest request = new CollectRequest();
            request.setRoot(new Dependency(
                    new DefaultArtifact(coords.groupId(), coords.artifactId(),
                            extension, coords.version()),
                    "compile"));
            request.addRepository(offlineRepo);

            return repositorySystem.collectDependencies(session, request);

        } catch (DependencyCollectionException e) {
            getLog().warn("Dependency collection errors for " + coords.gav()
                    + ": " + e.getMessage());
            // Return the partial result so we can still validate what was resolved.
            return e.getResult();
        }
    }

    /**
     * Validates the mojo configuration parameters.
     *
     * @throws MojoFailureException if the configuration is invalid
     */
    private void validateConfiguration() throws MojoFailureException {
        if (repositoryDirectory == null && repositoryZip == null) {
            throw new MojoFailureException(
                    "Either 'repositoryDirectory' or 'repositoryZip' must be configured.");
        }
        if (repositoryDirectory != null && repositoryZip != null) {
            throw new MojoFailureException(
                    "Only one of 'repositoryDirectory' or 'repositoryZip' can be configured.");
        }
        if (repositoryDirectory != null && !repositoryDirectory.isDirectory()) {
            throw new MojoFailureException(
                    "Configured repositoryDirectory is not a directory: " + repositoryDirectory);
        }
        if (repositoryZip != null && (!repositoryZip.isFile()
                || !repositoryZip.getName().endsWith(".zip"))) {
            throw new MojoFailureException(
                    "Configured repositoryZip is not a valid zip file: " + repositoryZip);
        }
        if (artifactIncludes == null || artifactIncludes.isEmpty()) {
            throw new MojoFailureException(
                    "'artifactIncludes' must be configured with at least one pattern.");
        }
    }

    /**
     * Extracts a zip file to a destination directory, guarding against zip-slip path traversal.
     *
     * @param zipFile the zip file to extract
     * @param destDir the destination directory
     * @throws IOException if an I/O error occurs or a zip-slip attack is detected
     */
    private void extractZip(File zipFile, Path destDir) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = destDir.resolve(entry.getName()).normalize();

                // Guard against zip-slip path traversal.
                if (!entryPath.startsWith(destDir)) {
                    throw new IOException(
                            "Zip entry outside target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zip.getInputStream(entry)) {
                        Files.copy(is, entryPath);
                    }
                }
            }
        }
    }

    /**
     * Detects the actual repository root after zip extraction. Handles multiple levels of
     * wrapping directories and looks for a {@code maven-repository} directory by name. If no
     * specifically named directory is found, falls back to drilling down through single-child
     * directories.
     *
     * @param extractDir the directory where the zip was extracted
     * @return the detected repository root path
     * @throws IOException if an I/O error occurs
     */
    Path detectRepoRoot(Path extractDir) throws IOException {
        Path current = extractDir;
        int maxDepth = 5;

        while (maxDepth-- > 0) {
            try (var children = Files.list(current)) {
                List<Path> items = children.toList();

                // Look for a directory named "maven-repository" at this level.
                for (Path item : items) {
                    if (Files.isDirectory(item)
                            && item.getFileName().toString().equals("maven-repository")) {
                        logVerbose("Found maven-repository directory: " + item);
                        return item;
                    }
                }

                // If there's a single subdirectory (possibly with non-directory files like
                // README.md alongside it), drill into it.
                List<Path> subdirs = items.stream()
                        .filter(Files::isDirectory)
                        .toList();
                if (subdirs.size() == 1) {
                    logVerbose("Drilling into single subdirectory: "
                            + subdirs.get(0).getFileName());
                    current = subdirs.get(0);
                    continue;
                }

                // Multiple subdirectories or no subdirectories — this is the repo root.
                break;
            }
        }

        logVerbose("Using repository root: " + current);
        return current;
    }

    /**
     * Scans a Maven repository directory for all POM files and extracts their GAV coordinates.
     *
     * @param repoDir the root directory of the Maven repository
     * @return the list of artifact coordinates found
     * @throws IOException if an I/O error occurs
     */
    List<ArtifactCoordinates> scanRepository(File repoDir) throws IOException {
        List<ArtifactCoordinates> artifacts = new ArrayList<>();
        MavenXpp3Reader reader = new MavenXpp3Reader();

        Files.walkFileTree(repoDir.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".pom")) {
                    try (InputStream is = Files.newInputStream(file)) {
                        Model model = reader.read(is);
                        String groupId = model.getGroupId() != null ? model.getGroupId()
                                : (model.getParent() != null
                                        ? model.getParent().getGroupId() : null);
                        String version = model.getVersion() != null ? model.getVersion()
                                : (model.getParent() != null
                                        ? model.getParent().getVersion() : null);
                        String artifactId = model.getArtifactId();
                        String packaging = model.getPackaging() != null
                                ? model.getPackaging() : "jar";

                        if (groupId != null && artifactId != null && version != null) {
                            artifacts.add(new ArtifactCoordinates(groupId, artifactId,
                                    version, packaging));
                            logVerbose("Found artifact: " + groupId + ":" + artifactId
                                    + ":" + version + " (" + packaging + ")");
                        } else {
                            getLog().warn("Skipping POM with incomplete GAV: " + file);
                        }
                    } catch (Exception e) {
                        getLog().warn("Failed to parse POM: " + file + " - "
                                + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return artifacts;
    }

    /**
     * Checks whether an artifact's artifactId matches any of the configured include patterns.
     *
     * @param coords the artifact coordinates to check
     * @return true if the artifact matches at least one include pattern
     */
    boolean matchesIncludes(ArtifactCoordinates coords) {
        for (String pattern : artifactIncludes) {
            if (SelectorUtils.match(pattern, coords.artifactId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether an artifact's artifactId matches any of the configured exclude patterns.
     *
     * @param coords the artifact coordinates to check
     * @return true if the artifact matches at least one exclude pattern
     */
    boolean matchesExcludes(ArtifactCoordinates coords) {
        for (String pattern : artifactExcludes) {
            if (SelectorUtils.match(pattern, coords.artifactId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursively deletes a temporary directory if it exists.
     *
     * @param dir the directory to delete
     */
    private void cleanupTempDir(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc)
                        throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            getLog().warn("Failed to clean up temp directory: " + dir + " - " + e.getMessage());
        }
    }

}
