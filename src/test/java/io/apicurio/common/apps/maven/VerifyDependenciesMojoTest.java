package io.apicurio.common.apps.maven;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for VerifyDependenciesMojo.
 */
class VerifyDependenciesMojoTest {

    private VerifyDependenciesMojo mojo;
    private Log mockLog;
    private Method isValidMethod;

    /**
     * Sets up the test environment before each test.
     *
     * @throws Exception if setup fails
     */
    @BeforeEach
    void setUp() throws Exception {
        mojo = new VerifyDependenciesMojo();
        mockLog = Mockito.mock(Log.class);
        mojo.setLog(mockLog);

        // Use reflection to access the private isValid method
        isValidMethod = VerifyDependenciesMojo.class.getDeclaredMethod("isValid", String.class);
        isValidMethod.setAccessible(true);
    }

    /**
     * Invokes the private isValid method using reflection.
     *
     * @param artifactPath the artifact path to validate
     * @return true if valid, false otherwise
     * @throws Exception if invocation fails
     */
    private boolean invokeIsValid(String artifactPath) throws Exception {
        return (boolean) isValidMethod.invoke(mojo, artifactPath);
    }

    @Test
    @DisplayName("Valid artifact with -redhat- in file name from directory")
    void testValidArtifact_RedhatDash_FromDirectory() throws Exception {
        String artifactPath = "/path/to/directory::hibernate-core-redhat-1.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with -redhat- in file name");
    }

    @Test
    @DisplayName("Valid artifact with .redhat- in file name from directory")
    void testValidArtifact_RedhatDot_FromDirectory() throws Exception {
        String artifactPath = "/path/to/directory::hibernate-core.redhat-00001.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with .redhat- in file name");
    }

    @Test
    @DisplayName("Valid artifact with -redhat- in file name from zip distribution")
    void testValidArtifact_RedhatDash_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::lib/hibernate-core-redhat-1.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with -redhat- in file name");
    }

    @Test
    @DisplayName("Valid artifact with .redhat- in file name from zip distribution")
    void testValidArtifact_RedhatDot_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::lib/hibernate-core.redhat-00001.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with .redhat- in file name");
    }

    @Test
    @DisplayName("Valid artifact with nested path in zip distribution")
    void testValidArtifact_NestedPath_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::path/to/nested/lib/hibernate-core-redhat-1.jar";
        assertTrue(invokeIsValid(artifactPath), "Should be valid with -redhat- in file name even with nested path");
    }

    @Test
    @DisplayName("Invalid artifact without redhat pattern from directory")
    void testInvalidArtifact_NoRedhat_FromDirectory() throws Exception {
        String artifactPath = "/path/to/directory::hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath), "Should be invalid without redhat pattern");
    }

    @Test
    @DisplayName("Invalid artifact without redhat pattern from zip distribution")
    void testInvalidArtifact_NoRedhat_FromZip() throws Exception {
        String artifactPath = "my-app-dist.zip::lib/hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath), "Should be invalid without redhat pattern");
    }

    @Test
    @DisplayName("BUG FIX: Invalid artifact when only zip name has -redhat- but jar doesn't")
    void testInvalidArtifact_RedhatInZipNameOnly() throws Exception {
        String artifactPath = "my-app-redhat-123.zip::lib/hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID when only zip name has -redhat- but jar file doesn't");
    }

    @Test
    @DisplayName("BUG FIX: Invalid artifact when only zip name has .redhat- but jar doesn't")
    void testInvalidArtifact_RedhatDotInZipNameOnly() throws Exception {
        String artifactPath = "my-app.redhat-00001.zip::lib/hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID when only zip name has .redhat- but jar file doesn't");
    }

    @Test
    @DisplayName("BUG FIX: Invalid artifact when directory path contains redhat but jar doesn't")
    void testInvalidArtifact_RedhatInDirectoryPathOnly() throws Exception {
        String artifactPath = "/path/to/redhat/directory::hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID when only directory path has redhat but jar file doesn't");
    }

    @Test
    @DisplayName("Valid artifact when both zip and jar have redhat pattern")
    void testValidArtifact_RedhatInBothZipAndJar() throws Exception {
        String artifactPath = "my-app-redhat-123.zip::lib/hibernate-core-redhat-1.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid when both zip and jar have redhat pattern");
    }

    @Test
    @DisplayName("Valid artifact with Windows-style path separators")
    void testValidArtifact_WindowsPathSeparators() throws Exception {
        String artifactPath = "my-app-dist.zip::lib\\hibernate-core-redhat-1.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid with Windows-style path separators");
    }

    @Test
    @DisplayName("Invalid artifact with Windows-style path separators")
    void testInvalidArtifact_WindowsPathSeparators() throws Exception {
        String artifactPath = "my-app-redhat-123.zip::lib\\hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be INVALID with Windows-style separators when only zip has redhat");
    }

    @Test
    @DisplayName("Valid artifact without :: separator (fallback case)")
    void testValidArtifact_NoSeparator() throws Exception {
        String artifactPath = "hibernate-core-redhat-1.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid for simple file name with -redhat-");
    }

    @Test
    @DisplayName("Invalid artifact without :: separator (fallback case)")
    void testInvalidArtifact_NoSeparator() throws Exception {
        String artifactPath = "hibernate-core-1.2.3.jar";
        assertFalse(invokeIsValid(artifactPath),
                "Should be invalid for simple file name without redhat pattern");
    }

    @Test
    @DisplayName("Valid artifact with -redhat- in middle of file name")
    void testValidArtifact_RedhatInMiddle() throws Exception {
        String artifactPath = "my-dist.zip::lib/hibernate-redhat-core-1.2.3.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid with -redhat- anywhere in file name");
    }

    @Test
    @DisplayName("Verbose mode logs invalid artifacts")
    void testVerboseMode_LogsInvalidArtifacts() throws Exception {
        // Enable verbose mode using reflection
        java.lang.reflect.Field verboseField = VerifyDependenciesMojo.class.getDeclaredField("verbose");
        verboseField.setAccessible(true);
        verboseField.set(mojo, true);

        when(mockLog.isInfoEnabled()).thenReturn(true);

        String artifactPath = "my-app-dist.zip::lib/hibernate-core-1.2.3.jar";
        boolean result = invokeIsValid(artifactPath);

        assertFalse(result, "Should be invalid");
        verify(mockLog).info(anyString());
    }

    @Test
    @DisplayName("Non-verbose mode does not log valid artifacts")
    void testNonVerboseMode_DoesNotLogValidArtifacts() throws Exception {
        // Verbose is false by default
        String artifactPath = "my-app-dist.zip::lib/hibernate-core-redhat-1.jar";
        boolean result = invokeIsValid(artifactPath);

        assertTrue(result, "Should be valid");
        verify(mockLog, never()).info(anyString());
    }

    @Test
    @DisplayName("Artifact with both -redhat- and .redhat- is valid")
    void testValidArtifact_BothRedhatPatterns() throws Exception {
        String artifactPath = "my-dist.zip::lib/hibernate-redhat-core.redhat-00001.jar";
        assertTrue(invokeIsValid(artifactPath),
                "Should be valid with both -redhat- and .redhat- patterns");
    }

    @Test
    @DisplayName("Edge case: Empty path after ::")
    void testEdgeCase_EmptyPathAfterSeparator() throws Exception {
        String artifactPath = "my-dist.zip::";
        assertFalse(invokeIsValid(artifactPath),
                "Should be invalid for empty path after separator");
    }

    @Test
    @DisplayName("Edge case: Only separator")
    void testEdgeCase_OnlySeparator() throws Exception {
        String artifactPath = "::";
        assertFalse(invokeIsValid(artifactPath),
                "Should be invalid for only separator");
    }
}
