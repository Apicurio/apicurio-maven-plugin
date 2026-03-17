package io.apicurio.common.apps.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.SelectorUtils;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;

/**
 * Base class for verification mojos that check whether Maven dependencies are productized
 * (have a {@code -redhat-} or {@code .redhat-} version suffix). Provides shared validation
 * logic, reporting, CSV output, and verbose logging utilities.
 */
public abstract class AbstractVerifyMojo extends AbstractMojo {

    /**
     * List of groupId:artifactId patterns for dependencies to ignore during validation.
     * Supports wildcard patterns using {@code *} (e.g. {@code io.apicurio:*}).
     * Matching dependencies will not be flagged even if they are unproductized.
     */
    @Parameter(property = "ignoreGAVs")
    List<String> ignoreGAVs;

    /**
     * Enable verbose output for debugging. When enabled, provides detailed information about
     * dependency tree resolution and validation results.
     */
    @Parameter(property = "verify.verbose", defaultValue = "false")
    boolean verbose;

    /**
     * Optional path to a CSV output file. When set, a deduplicated flat list of all unproductized
     * dependencies will be written to this file with columns: groupId, artifactId, version.
     * The list is sorted by groupId:artifactId:version.
     */
    @Parameter(property = "outputCsv")
    File outputCsv;

    /**
     * Walks a dependency tree and records any unproductized dependencies. Skips the root node
     * (the artifact being checked) and any dependencies matching ignore patterns. When an
     * unproductized dependency is found, the full dependency path from the root down to the
     * unproductized dependency is included in the output.
     *
     * @param root the root node of the dependency tree
     * @param artifactGav the GAV of the artifact being checked (for error messages)
     * @param unproductizedDependencies the set to add hierarchy violation messages to
     * @param unproductizedGavs the set to add flat groupId:artifactId:version strings to
     */
    protected void validateDependencyTree(DependencyNode root, String artifactGav,
            Set<String> unproductizedDependencies, Set<String> unproductizedGavs) {
        Deque<DependencyNode> path = new ArrayDeque<>();
        root.accept(new DependencyVisitor() {
            @Override
            public boolean visitEnter(DependencyNode node) {
                path.push(node);

                // Skip the root node (it's the artifact we're checking, not a dependency).
                if (node == root) {
                    return true;
                }

                if (node.getDependency() == null || node.getArtifact() == null) {
                    return true;
                }

                String depGroupId = node.getArtifact().getGroupId();
                String depArtifactId = node.getArtifact().getArtifactId();
                String depVersion = node.getArtifact().getVersion();
                String depScope = node.getDependency().getScope();

                if (isIgnored(depGroupId, depArtifactId)) {
                    logVerbose("Ignoring dependency: " + depGroupId + ":" + depArtifactId
                            + " in tree of " + artifactGav);
                    return true;
                }

                if (!isProductized(depVersion)) {
                    String hierarchyMsg = buildHierarchyMessage(path);
                    unproductizedDependencies.add(hierarchyMsg);
                    unproductizedGavs.add(depGroupId + ":" + depArtifactId + ":" + depVersion);
                    logVerbose("Unproductized dependency: " + depGroupId + ":" + depArtifactId
                            + ":" + depVersion + " (" + depScope + ")");
                }

                return true;
            }

            @Override
            public boolean visitLeave(DependencyNode node) {
                path.pop();
                return true;
            }
        });
    }

    /**
     * Builds a human-readable hierarchy message showing the full dependency path from the root
     * down to the unproductized dependency.
     *
     * @param path the stack of dependency nodes from root to the unproductized dependency
     * @return a formatted string showing the full dependency chain
     */
    protected String buildHierarchyMessage(Deque<DependencyNode> path) {
        StringBuilder sb = new StringBuilder();
        Iterator<DependencyNode> it = path.descendingIterator();
        int depth = 0;
        while (it.hasNext()) {
            DependencyNode node = it.next();
            if (node.getArtifact() == null) {
                continue;
            }
            if (depth > 0) {
                sb.append("\n");
                sb.append("  ".repeat(depth));
                sb.append("-> ");
            }
            sb.append(node.getArtifact().getGroupId())
                    .append(":")
                    .append(node.getArtifact().getArtifactId())
                    .append(":")
                    .append(node.getArtifact().getVersion());
            if (node.getDependency() != null && node.getDependency().getScope() != null
                    && !node.getDependency().getScope().isEmpty()) {
                sb.append(" (").append(node.getDependency().getScope()).append(")");
            }
            depth++;
        }
        return sb.toString();
    }

    /**
     * Reports the validation results and throws a {@link MojoFailureException} if any violations
     * or resolution errors were found.
     *
     * @param unproductizedDependencies the set of unproductized dependency descriptions
     * @param resolutionErrors the set of dependency resolution error descriptions
     * @throws MojoFailureException if any violations or errors were found
     */
    protected void reportResults(Set<String> unproductizedDependencies,
            Set<String> resolutionErrors) throws MojoFailureException {

        getLog().info("  Unproductized dependencies: " + unproductizedDependencies.size());
        getLog().info("  Resolution errors:          " + resolutionErrors.size());

        boolean hasFailures = !unproductizedDependencies.isEmpty()
                || !resolutionErrors.isEmpty();

        if (!hasFailures) {
            getLog().info("All dependencies are properly productized.");
            return;
        }

        StringBuilder failureMessage = new StringBuilder();

        if (!unproductizedDependencies.isEmpty()) {
            failureMessage.append("\nUnproductized dependencies:\n");
            for (String dep : unproductizedDependencies) {
                failureMessage.append("    ")
                        .append(dep.replace("\n", "\n    "))
                        .append("\n\n");
            }
        }

        if (!resolutionErrors.isEmpty()) {
            failureMessage.append("\nDependency resolution errors:\n");
            for (String err : resolutionErrors) {
                failureMessage.append("    ").append(err).append("\n");
            }
        }

        throw new MojoFailureException(failureMessage.toString());
    }

    /**
     * Writes a CSV file containing the deduplicated list of unproductized dependencies,
     * sorted by groupId:artifactId:version. Only writes the file if {@link #outputCsv} is
     * configured and there are unproductized dependencies to report.
     *
     * @param unproductizedGavs the deduplicated set of groupId:artifactId:version strings
     * @throws IOException if the file cannot be written
     */
    protected void writeCsvReport(Set<String> unproductizedGavs) throws IOException {
        if (outputCsv == null || unproductizedGavs.isEmpty()) {
            return;
        }

        Path csvPath = outputCsv.toPath();
        if (csvPath.getParent() != null) {
            Files.createDirectories(csvPath.getParent());
        }

        StringBuilder csv = new StringBuilder();
        csv.append("groupId,artifactId,version\n");
        for (String gav : unproductizedGavs) {
            String[] parts = gav.split(":", 3);
            csv.append(parts[0]).append(",").append(parts[1]).append(",")
                    .append(parts[2]).append("\n");
        }

        Files.writeString(csvPath, csv.toString());
        getLog().info("CSV report written to: " + csvPath.toAbsolutePath());
    }

    /**
     * Checks whether a version string indicates a productized build.
     *
     * @param version the version string to check
     * @return {@code true} if the version contains {@code -redhat-} or {@code .redhat-}
     */
    protected boolean isProductized(String version) {
        if (version == null) {
            return false;
        }
        return version.contains("-redhat-") || version.contains(".redhat-");
    }

    /**
     * Checks whether a dependency should be ignored based on the configured ignore patterns.
     *
     * @param groupId the dependency's groupId
     * @param artifactId the dependency's artifactId
     * @return {@code true} if the dependency matches any ignore pattern
     */
    protected boolean isIgnored(String groupId, String artifactId) {
        if (groupId == null || artifactId == null) {
            return false;
        }
        for (String pattern : ignoreGAVs) {
            String[] parts = pattern.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            if (SelectorUtils.match(parts[0], groupId)
                    && SelectorUtils.match(parts[1], artifactId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs the full dependency tree when verbose mode is enabled.
     *
     * @param root the root node of the dependency tree
     */
    protected void logVerboseDependencyTree(DependencyNode root) {
        if (!verbose) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Dependency tree:\n");
        appendTreeNode(sb, root, 0);
        getLog().info("[VERBOSE] " + sb);
    }

    /**
     * Recursively appends a dependency tree node and its children to the string builder.
     *
     * @param sb the string builder to append to
     * @param node the current dependency node
     * @param depth the current depth in the tree (for indentation)
     */
    private void appendTreeNode(StringBuilder sb, DependencyNode node, int depth) {
        if (node.getArtifact() != null) {
            sb.append("  ".repeat(depth));
            if (depth > 0) {
                sb.append("+- ");
            }
            sb.append(node.getArtifact().getGroupId())
                    .append(":")
                    .append(node.getArtifact().getArtifactId())
                    .append(":")
                    .append(node.getArtifact().getVersion());
            if (node.getDependency() != null && node.getDependency().getScope() != null
                    && !node.getDependency().getScope().isEmpty()) {
                sb.append(" (").append(node.getDependency().getScope()).append(")");
            }
            if (node.getDependency() != null && node.getDependency().isOptional()) {
                sb.append(" [optional]");
            }
            sb.append("\n");
        }
        for (DependencyNode child : node.getChildren()) {
            appendTreeNode(sb, child, depth + 1);
        }
    }

    /**
     * Logs a message when verbose mode is enabled.
     *
     * @param message the message to log
     */
    protected void logVerbose(String message) {
        if (verbose) {
            getLog().info("[VERBOSE] " + message);
        }
    }

}
