package io.apicurio.common.apps.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
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
 * Verifies that all transitive dependencies of a single Maven artifact are productized
 * (have a {@code -redhat-} or {@code .redhat-} version suffix). The build fails if any
 * unproductized dependencies are found.
 *
 * <p>This mojo does not require a Maven project and can be run directly from the command
 * line. Example usage:
 * <pre>{@code
 * mvn io.apicurio:apicurio-maven-plugin:0.0.5-SNAPSHOT:verify-artifact-dependencies \
 *     -Dartifact=io.apicurio:apicurio-registry-app:3.0.0-redhat-00001 \
 *     -DremoteRepositories=https://repo1.maven.org/maven2
 * }</pre>
 *
 * <p>Multiple remote repositories can be specified as a comma-separated list. A local
 * repository can be specified to resolve artifacts from an offline repository on disk.
 */
@Mojo(name = "verify-artifact-dependencies", requiresProject = false)
public class VerifyArtifactDependenciesMojo extends AbstractVerifyMojo {

    /**
     * The artifact coordinates in {@code groupId:artifactId:version} format. An optional
     * fourth segment may specify the packaging/extension (e.g.
     * {@code groupId:artifactId:version:pom}). Defaults to {@code jar} if omitted.
     */
    @Parameter(property = "artifact", required = true)
    String artifact;

    /**
     * Comma-separated list of remote repository URLs to use for dependency resolution.
     * Each URL can optionally include an ID prefix in the format {@code id::url}
     * (e.g. {@code central::https://repo1.maven.org/maven2}). If no ID is provided,
     * one is generated automatically.
     */
    @Parameter(property = "remoteRepositories")
    List<String> remoteRepositories;

    /**
     * Path to a local Maven repository directory to use for resolution. This can be a
     * standard {@code ~/.m2/repository} or an offline repository on disk. If not specified,
     * the default Maven local repository is used.
     */
    @Parameter(property = "localRepository")
    File localRepository;

    @SuppressWarnings("deprecation")
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            if (ignoreGAVs == null) {
                ignoreGAVs = List.of();
            }

            // Parse the artifact coordinates.
            String[] parts = artifact.split(":");
            if (parts.length < 3 || parts.length > 4) {
                throw new MojoFailureException(
                        "Invalid artifact format: '" + artifact
                                + "'. Expected groupId:artifactId:version"
                                + " or groupId:artifactId:version:packaging");
            }
            String groupId = parts[0];
            String artifactId = parts[1];
            String version = parts[2];
            String extension = parts.length == 4 ? parts[3] : "jar";
            String artifactGav = groupId + ":" + artifactId + ":" + version;

            getLog().info("Verifying dependencies for: " + artifactGav
                    + " (" + extension + ")");

            // Build the list of remote repositories.
            List<RemoteRepository> repos = buildRemoteRepositories();
            if (repos.isEmpty()) {
                getLog().warn("No remote repositories configured. Resolution may fail"
                        + " unless all artifacts are in the local repository.");
            } else {
                for (RemoteRepository repo : repos) {
                    getLog().info("Using remote repository: " + repo.getId()
                            + " (" + repo.getUrl() + ")");
                }
            }

            // Configure the session with a custom local repository if specified.
            RepositorySystemSession session = configureSession();

            // Collect the dependency tree.
            CollectResult result = collectDependencyTree(session, repos, groupId,
                    artifactId, version, extension);

            if (result == null || result.getRoot() == null) {
                throw new MojoFailureException(
                        "Failed to collect dependency tree for: " + artifactGav);
            }

            Set<String> resolutionErrors = new TreeSet<>();

            // Report any collection exceptions as resolution errors.
            if (!result.getExceptions().isEmpty()) {
                for (Exception ex : result.getExceptions()) {
                    resolutionErrors.add(artifactGav + ": " + ex.getMessage());
                }
            }

            // Log the full dependency tree if verbose is enabled.
            logVerboseDependencyTree(result.getRoot());

            // Walk the dependency tree and check each dependency's version.
            Set<String> unproductizedDependencies = new TreeSet<>();
            Set<String> unproductizedGavs = new TreeSet<>();
            validateDependencyTree(result.getRoot(), artifactGav,
                    unproductizedDependencies, unproductizedGavs);

            // Write CSV report (before reportResults, which may throw).
            writeCsvReport(unproductizedGavs);

            // Report results.
            getLog().info("=== Artifact Dependency Verification Results ===");
            reportResults(unproductizedDependencies, resolutionErrors);

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Error verifying artifact dependencies", e);
        }
    }

    /**
     * Collects the transitive dependency tree for the specified artifact using Maven's
     * Aether resolver.
     *
     * @param session the repository session
     * @param repos the remote repositories to use for resolution
     * @param groupId the artifact's groupId
     * @param artifactId the artifact's artifactId
     * @param version the artifact's version
     * @param extension the artifact's extension/packaging
     * @return the collect result containing the dependency tree, or null on complete failure
     */
    private CollectResult collectDependencyTree(RepositorySystemSession session,
            List<RemoteRepository> repos, String groupId, String artifactId,
            String version, String extension) {
        try {
            CollectRequest request = new CollectRequest();
            request.setRoot(new Dependency(
                    new DefaultArtifact(groupId, artifactId, extension, version),
                    "compile"));
            request.setRepositories(repos);

            return repositorySystem.collectDependencies(session, request);

        } catch (DependencyCollectionException e) {
            getLog().warn("Dependency collection errors for " + groupId + ":" + artifactId
                    + ":" + version + ": " + e.getMessage());
            // Return the partial result so we can still validate what was resolved.
            return e.getResult();
        }
    }

    /**
     * Builds the list of remote repositories from the configured repository URLs.
     *
     * @return the list of remote repositories
     */
    private List<RemoteRepository> buildRemoteRepositories() {
        List<RemoteRepository> repos = new ArrayList<>();
        if (remoteRepositories == null || remoteRepositories.isEmpty()) {
            return repos;
        }

        int index = 0;
        for (String repoSpec : remoteRepositories) {
            String id;
            String url;

            if (repoSpec.contains("::")) {
                String[] repoParts = repoSpec.split("::", 2);
                id = repoParts[0];
                url = repoParts[1];
            } else {
                id = "repo-" + index;
                url = repoSpec;
            }

            repos.add(new RemoteRepository.Builder(id, "default", url).build());
            index++;
        }

        return repos;
    }

    /**
     * Configures the repository session, optionally overriding the local repository path.
     *
     * @return the configured session
     */
    private RepositorySystemSession configureSession() {
        if (localRepository == null) {
            return repoSession;
        }

        getLog().info("Using local repository: " + localRepository.getAbsolutePath());
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(
                repoSession);
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(
                session, new LocalRepository(localRepository)));
        return session;
    }

}
