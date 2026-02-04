package io.apicurio.common.apps.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.SelectorUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Mojo(name = "verify-dependencies")
public class VerifyDependenciesMojo extends AbstractMojo {

    @Parameter
    List<String> fileTypes;

    @Parameter
    List<File> directories;

    @Parameter
    List<File> distributions;

    @Parameter
    List<String> ignoreFiles;

    /**
     * Enable verbose output for debugging. When enabled, provides detailed information about:
     * - Files being scanned in each directory and distribution
     * - File type detection and filtering
     * - Validation results for each artifact
     * - Pattern matching for ignored files
     */
    @Parameter(property = "verify.verbose", defaultValue = "false")
    boolean verbose;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            if (fileTypes == null) {
                fileTypes = List.of("jar");
            }
            if (directories == null) {
                directories = List.of();
            }
            if (distributions == null) {
                distributions = List.of();
            }
            if (ignoreFiles == null) {
                ignoreFiles = List.of();
            }

            getLog().info("Verifying dependencies in " + directories.size() + " directories and "
                    + distributions.size() + " distributions");
            logVerbose("File types to search for: " + fileTypes);
            logVerbose("Ignore patterns: " + ignoreFiles);

            // Find the files we want to validate.
            Set<String> filesToValidate = new TreeSet<>();

            // Find files in configured directories.
            for (File directory : directories) {
                if (!directory.isDirectory()) {
                    throw new MojoFailureException("Configured directory is not a directory: " + directory);
                }
                Path dirPath = directory.getCanonicalFile().toPath();
                logVerbose("Scanning directory: " + dirPath);

                Set<String> foundInDir = Files.list(dirPath).filter(Files::isRegularFile)
                        .filter(file -> isDependencyJarFile(file.toString()))
                        .map(file -> dirPath.toString() + "::" + file.getFileName())
                        .collect(Collectors.toSet());

                logVerbose("Found " + foundInDir.size() + " dependency files in directory: " + dirPath);
                if (verbose) {
                    foundInDir.forEach(file -> logVerbose("  - " + file));
                }
                filesToValidate.addAll(foundInDir);
            }

            if (filesToValidate.isEmpty()) {
                throw new MojoFailureException("Found 0 dependencies (from configured sources) to verify!");
            }

            logVerbose("Total files found in directories: " + filesToValidate.size());

            // Find files in configured distributions.
            for (File distribution : distributions) {
                logVerbose("Scanning distribution: " + distribution.getName());
                Set<String> foundInDist = findAllInZip(distribution);
                logVerbose("Found " + foundInDist.size() + " dependency files in distribution: "
                        + distribution.getName());
                if (verbose) {
                    foundInDist.forEach(file -> logVerbose("  - " + file));
                }
                filesToValidate.addAll(foundInDist);
            }

            // Validate those files.
            getLog().info("Validating " + filesToValidate.size() + " total dependency files");
            Set<String> invalidArtifacts = new TreeSet<>();
            Set<String> ignoredArtifacts = new TreeSet<>();
            Set<String> validArtifacts = new TreeSet<>();

            filesToValidate.forEach(file -> {
                if (isIgnore(file)) {
                    logVerbose("Ignoring file: " + file);
                    ignoredArtifacts.add(file);
                } else if (!isValid(file)) {
                    logVerbose("Invalid file (missing -redhat- or .redhat-): " + file);
                    invalidArtifacts.add(file);
                } else {
                    logVerbose("Valid file: " + file);
                    validArtifacts.add(file);
                }
            });

            getLog().info("Validation results:");
            getLog().info("  Valid artifacts: " + validArtifacts.size());
            getLog().info("  Invalid artifacts: " + invalidArtifacts.size());
            getLog().info("  Ignored artifacts: " + ignoredArtifacts.size());

            if (!invalidArtifacts.isEmpty()) {
                String serializedInvalidArtifacts = serialize(invalidArtifacts);
                throw new MojoFailureException("Invalid dependencies found: \n" + serializedInvalidArtifacts);
            }
        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot build project dependency graph", e);
        }
    }

    /**
     * Finds all dependency files within a distribution (zip file).
     *
     * @param distribution the distribution file to scan
     * @return the set of dependency file paths found
     * @throws IOException if an I/O error occurs
     */
    private Set<String> findAllInZip(File distribution) throws IOException {
        Set<String> foundFiles = new TreeSet<>();
        int totalEntries = 0;
        int regularFileEntries = 0;
        int dependencyFileEntries = 0;

        try (ZipFile zipFile = new ZipFile(distribution)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                totalEntries++;

                if (!entry.isDirectory()) {
                    regularFileEntries++;
                    String entryName = entry.getName();

                    if (isDependencyJarFile(entryName)) {
                        dependencyFileEntries++;
                        String filePath = distribution.getName() + "::" + entryName;
                        foundFiles.add(filePath);
                        logVerbose("Found dependency in zip: " + filePath);
                    } else {
                        logVerbose("Skipping non-dependency file in zip: " + entryName);
                    }
                }
            }
        }

        logVerbose("Distribution " + distribution.getName() + " scan summary:");
        logVerbose("  Total entries: " + totalEntries);
        logVerbose("  Regular file entries: " + regularFileEntries);
        logVerbose("  Dependency file entries: " + dependencyFileEntries);

        return foundFiles;
    }

    /**
     * Determines if a file is a dependency jar file based on its extension.
     *
     * @param file the file path to check
     * @return true if the file is a dependency jar file, false otherwise
     */
    protected boolean isDependencyJarFile(String file) {
        int dotIdx = file.lastIndexOf('.');
        if (dotIdx == -1) {
            logVerbose("File has no extension, skipping: " + file);
            return false;
        }

        String extension = file.substring(dotIdx + 1);
        boolean isDependencyFile = fileTypes.contains(extension);

        if (!isDependencyFile) {
            logVerbose("File extension '" + extension + "' not in fileTypes " + fileTypes + ": " + file);
        }

        return isDependencyFile;
    }

    /**
     * Checks if an artifact is valid (productized with -redhat- or .redhat-).
     *
     * @param artifactPath the artifact path to check
     * @return true if the artifact is valid, false otherwise
     */
    private boolean isValid(String artifactPath) {
        // Extract the file name from the artifact path
        // Format: "prefix::path/to/file" where prefix is either a directory path or distribution name
        String fileName;
        if (artifactPath.contains("::")) {
            String pathPart = artifactPath.substring(artifactPath.indexOf("::") + 2);
            // Extract just the file name from the path (handles both / and \ separators)
            int lastSlash = Math.max(pathPart.lastIndexOf('/'), pathPart.lastIndexOf('\\'));
            fileName = lastSlash >= 0 ? pathPart.substring(lastSlash + 1) : pathPart;
        } else {
            // Fallback if format is unexpected
            fileName = artifactPath;
        }
        
        boolean hasRedhatDash = fileName.contains("-redhat-");
        boolean hasRedhatDot = fileName.contains(".redhat-");
        boolean valid = hasRedhatDash || hasRedhatDot;

        if (verbose && !valid) {
            logVerbose("Artifact is not valid (missing -redhat- or .redhat-): " + artifactPath);
        }

        return valid;
    }

    /**
     * Checks if an artifact should be ignored based on configured ignore patterns.
     *
     * @param artifactPath the artifact path to check
     * @return true if the artifact should be ignored, false otherwise
     */
    private boolean isIgnore(String artifactPath) {
        for (String ignorePattern : ignoreFiles) {
            if (SelectorUtils.match(ignorePattern, artifactPath)) {
                logVerbose("Artifact matches ignore pattern '" + ignorePattern + "': " + artifactPath);
                return true;
            }
        }
        return false;
    }

    private static String serialize(Set<String> invalidArtifacts) {
        StringBuilder sb = new StringBuilder();
        for (String artifact : invalidArtifacts) {
            sb.append("    ");
            sb.append(artifact);
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Logs a message when verbose mode is enabled.
     *
     * @param message the message to log
     */
    private void logVerbose(String message) {
        if (verbose) {
            getLog().info("[VERBOSE] " + message);
        }
    }

}
