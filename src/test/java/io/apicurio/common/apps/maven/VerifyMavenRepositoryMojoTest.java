package io.apicurio.common.apps.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.apicurio.common.apps.maven.VerifyMavenRepositoryMojo.ArtifactCoordinates;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link VerifyMavenRepositoryMojo}.
 */
@ExtendWith(MockitoExtension.class)
class VerifyMavenRepositoryMojoTest {

    private VerifyMavenRepositoryMojo mojo;

    @Mock
    private Log mockLog;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mojo = new VerifyMavenRepositoryMojo();
        mojo.setLog(mockLog);
        mojo.ignoreGAVs = List.of();
        mojo.artifactIncludes = List.of("*");
        mojo.artifactExcludes = List.of();
        mojo.verbose = false;
    }

    // ========================================================================
    // isProductized tests
    // ========================================================================

    @Test
    void testIsProductized_redhatDash() {
        assertTrue(mojo.isProductized("1.0.0-redhat-00001"));
    }

    @Test
    void testIsProductized_redhatDot() {
        assertTrue(mojo.isProductized("1.0.0.redhat-00001"));
    }

    @Test
    void testIsProductized_notProductized() {
        assertFalse(mojo.isProductized("1.0.0"));
    }

    @Test
    void testIsProductized_snapshot() {
        assertFalse(mojo.isProductized("1.0.0-SNAPSHOT"));
    }

    @Test
    void testIsProductized_null() {
        assertFalse(mojo.isProductized(null));
    }

    // ========================================================================
    // isIgnored tests
    // ========================================================================

    @Test
    void testIsIgnored_exactMatch() {
        mojo.ignoreGAVs = List.of("io.apicurio:apicurio-common");
        assertTrue(mojo.isIgnored("io.apicurio", "apicurio-common"));
    }

    @Test
    void testIsIgnored_wildcardArtifactId() {
        mojo.ignoreGAVs = List.of("io.apicurio:*");
        assertTrue(mojo.isIgnored("io.apicurio", "apicurio-common"));
        assertTrue(mojo.isIgnored("io.apicurio", "apicurio-registry"));
    }

    @Test
    void testIsIgnored_wildcardGroupId() {
        mojo.ignoreGAVs = List.of("*:my-artifact");
        assertTrue(mojo.isIgnored("com.example", "my-artifact"));
        assertTrue(mojo.isIgnored("org.other", "my-artifact"));
    }

    @Test
    void testIsIgnored_noMatch() {
        mojo.ignoreGAVs = List.of("io.apicurio:*");
        assertFalse(mojo.isIgnored("com.example", "some-lib"));
    }

    @Test
    void testIsIgnored_emptyPatterns() {
        mojo.ignoreGAVs = List.of();
        assertFalse(mojo.isIgnored("io.apicurio", "apicurio-common"));
    }

    @Test
    void testIsIgnored_nullGroupId() {
        mojo.ignoreGAVs = List.of("io.apicurio:*");
        assertFalse(mojo.isIgnored(null, "apicurio-common"));
    }

    // ========================================================================
    // matchesIncludes / matchesExcludes tests
    // ========================================================================

    @Test
    void testMatchesIncludes_wildcard() {
        mojo.artifactIncludes = List.of("apicurio-*");
        assertTrue(mojo.matchesIncludes(
                new ArtifactCoordinates("io.apicurio", "apicurio-registry", "1.0.0", "jar")));
        assertFalse(mojo.matchesIncludes(
                new ArtifactCoordinates("com.example", "jackson-core", "2.0.0", "jar")));
    }

    @Test
    void testMatchesIncludes_exact() {
        mojo.artifactIncludes = List.of("apicurio-registry");
        assertTrue(mojo.matchesIncludes(
                new ArtifactCoordinates("io.apicurio", "apicurio-registry", "1.0.0", "jar")));
        assertFalse(mojo.matchesIncludes(
                new ArtifactCoordinates("io.apicurio", "apicurio-common", "1.0.0", "jar")));
    }

    @Test
    void testMatchesIncludes_multiplePatterns() {
        mojo.artifactIncludes = List.of("apicurio-*", "strimzi-*");
        assertTrue(mojo.matchesIncludes(
                new ArtifactCoordinates("io.apicurio", "apicurio-registry", "1.0.0", "jar")));
        assertTrue(mojo.matchesIncludes(
                new ArtifactCoordinates("io.strimzi", "strimzi-api", "1.0.0", "jar")));
        assertFalse(mojo.matchesIncludes(
                new ArtifactCoordinates("com.example", "other-lib", "1.0.0", "jar")));
    }

    @Test
    void testMatchesExcludes_wildcard() {
        mojo.artifactExcludes = List.of("*-parent");
        assertTrue(mojo.matchesExcludes(
                new ArtifactCoordinates("io.apicurio", "apicurio-parent", "1.0.0", "pom")));
        assertFalse(mojo.matchesExcludes(
                new ArtifactCoordinates("io.apicurio", "apicurio-registry", "1.0.0", "jar")));
    }

    @Test
    void testMatchesExcludes_emptyList() {
        mojo.artifactExcludes = List.of();
        assertFalse(mojo.matchesExcludes(
                new ArtifactCoordinates("io.apicurio", "apicurio-registry", "1.0.0", "jar")));
    }

    // ========================================================================
    // scanRepository tests
    // ========================================================================

    @Test
    void testScanRepository_findsArtifacts() throws Exception {
        Path repoDir = createDirectoryRepo(tempDir, "1.0.0-redhat-00001");
        List<ArtifactCoordinates> artifacts = mojo.scanRepository(repoDir.toFile());
        assertEquals(1, artifacts.size());
        assertEquals("io.apicurio", artifacts.get(0).groupId());
        assertEquals("my-lib", artifacts.get(0).artifactId());
        assertEquals("1.0.0-redhat-00001", artifacts.get(0).version());
        assertEquals("jar", artifacts.get(0).packaging());
    }

    @Test
    void testScanRepository_multipleArtifacts() throws Exception {
        Path repoDir = tempDir.resolve("repository");

        createArtifactPom(repoDir, "io.apicurio", "apicurio-registry",
                "3.0.0-redhat-00001");
        createArtifactPom(repoDir, "com.fasterxml.jackson.core", "jackson-core",
                "2.15.0-redhat-00001");

        List<ArtifactCoordinates> artifacts = mojo.scanRepository(repoDir.toFile());
        assertEquals(2, artifacts.size());
    }

    @Test
    void testScanRepository_emptyDirectory() throws Exception {
        Path repoDir = tempDir.resolve("empty-repo");
        Files.createDirectories(repoDir);
        List<ArtifactCoordinates> artifacts = mojo.scanRepository(repoDir.toFile());
        assertTrue(artifacts.isEmpty());
    }

    // ========================================================================
    // detectRepoRoot tests
    // ========================================================================

    @Test
    void testDetectRepoRoot_noExtraDir() throws Exception {
        Path extractDir = tempDir.resolve("extract");
        Files.createDirectories(extractDir.resolve("io/apicurio"));
        Files.createDirectories(extractDir.resolve("com/example"));

        Path root = mojo.detectRepoRoot(extractDir);
        assertEquals(extractDir, root);
    }

    @Test
    void testDetectRepoRoot_withExtraDir() throws Exception {
        Path extractDir = tempDir.resolve("extract");
        Path innerDir = extractDir.resolve("maven-repository");
        Files.createDirectories(innerDir.resolve("io/apicurio"));

        Path root = mojo.detectRepoRoot(extractDir);
        assertEquals(innerDir, root);
    }

    // ========================================================================
    // Configuration validation tests
    // ========================================================================

    @Test
    void testExecute_neitherDirectoryNorZip() {
        mojo.repositoryDirectory = null;
        mojo.repositoryZip = null;
        assertThrows(MojoFailureException.class, () -> mojo.execute());
    }

    @Test
    void testExecute_bothDirectoryAndZip() {
        mojo.repositoryDirectory = tempDir.toFile();
        mojo.repositoryZip = tempDir.resolve("repo.zip").toFile();
        assertThrows(MojoFailureException.class, () -> mojo.execute());
    }

    @Test
    void testExecute_missingArtifactIncludes() {
        mojo.repositoryDirectory = tempDir.toFile();
        mojo.artifactIncludes = null;
        assertThrows(MojoFailureException.class, () -> mojo.execute());
    }

    @Test
    void testExecute_emptyArtifactIncludes() {
        mojo.repositoryDirectory = tempDir.toFile();
        mojo.artifactIncludes = List.of();
        assertThrows(MojoFailureException.class, () -> mojo.execute());
    }

    // ========================================================================
    // Helper methods for creating test repositories
    // ========================================================================

    /**
     * Creates a directory-based Maven repository with a single artifact.
     */
    private Path createDirectoryRepo(Path baseDir, String version) throws IOException {
        Path repoDir = baseDir.resolve("repository");
        createArtifactPom(repoDir, "io.apicurio", "my-lib", version);
        return repoDir;
    }

    /**
     * Creates a single artifact POM in the repository directory structure.
     */
    private void createArtifactPom(Path repoDir, String groupId, String artifactId,
            String version) throws IOException {
        String groupPath = groupId.replace('.', '/');
        Path artifactDir = repoDir.resolve(groupPath + "/" + artifactId + "/" + version);
        Files.createDirectories(artifactDir);
        Files.writeString(artifactDir.resolve(artifactId + "-" + version + ".pom"),
                createPomXml(groupId, artifactId, version, List.of()));
    }

    /**
     * Creates a minimal POM XML string.
     */
    private String createPomXml(String groupId, String artifactId, String version,
            List<String> dependencyXmlFragments) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n");
        sb.append("    <groupId>").append(groupId).append("</groupId>\n");
        sb.append("    <artifactId>").append(artifactId).append("</artifactId>\n");
        sb.append("    <version>").append(version).append("</version>\n");

        if (!dependencyXmlFragments.isEmpty()) {
            sb.append("    <dependencies>\n");
            for (String depXml : dependencyXmlFragments) {
                sb.append(depXml);
            }
            sb.append("    </dependencies>\n");
        }

        sb.append("</project>\n");
        return sb.toString();
    }

}
