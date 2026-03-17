package io.apicurio.common.apps.maven;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Validates that all compile and runtime scoped transitive dependencies of the current Maven
 * project are productized (have a {@code -redhat-} or {@code .redhat-} version suffix).
 *
 * <p>This mojo is intended to be used in productized builds to ensure that all dependencies
 * in the build have been built from source in PNC. It uses Maven's Aether dependency resolver
 * to collect the full transitive dependency tree of the project, then walks the tree and
 * checks each dependency's version for the required productization suffix.
 *
 * <p>Typical usage is to enable this mojo via a Maven profile that is only activated during
 * productized builds:
 * <pre>{@code
 * <profile>
 *     <id>productized</id>
 *     <build>
 *         <plugins>
 *             <plugin>
 *                 <groupId>io.apicurio</groupId>
 *                 <artifactId>apicurio-maven-plugin</artifactId>
 *                 <executions>
 *                     <execution>
 *                         <goals>
 *                             <goal>verify-project-dependencies</goal>
 *                         </goals>
 *                     </execution>
 *                 </executions>
 *             </plugin>
 *         </plugins>
 *     </build>
 * </profile>
 * }</pre>
 */
@Mojo(name = "verify-project-dependencies", defaultPhase = LifecyclePhase.VERIFY,
        threadSafe = true)
public class VerifyProjectDependenciesMojo extends AbstractVerifyMojo {

    /**
     * The current Maven project whose dependencies will be validated.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @SuppressWarnings("deprecation")
    @Component
    private RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    /**
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            if (ignoreGAVs == null) {
                ignoreGAVs = List.of();
            }

            String projectGav = project.getGroupId() + ":" + project.getArtifactId()
                    + ":" + project.getVersion();
            getLog().info("Verifying dependencies for: " + projectGav);

            // Collect the full transitive dependency tree using Aether.
            CollectResult result = collectDependencyTree();

            Set<String> resolutionErrors = new TreeSet<>();
            if (result == null) {
                throw new MojoFailureException(
                        "Failed to collect dependency tree for " + projectGav);
            }

            // Report any collection exceptions as resolution errors.
            if (!result.getExceptions().isEmpty()) {
                for (Exception ex : result.getExceptions()) {
                    resolutionErrors.add(projectGav + ": " + ex.getMessage());
                }
            }

            // Walk the dependency tree and check each dependency's version.
            Set<String> unproductizedDependencies = new TreeSet<>();
            Set<String> unproductizedGavs = new TreeSet<>();

            if (result.getRoot() != null) {
                logVerboseDependencyTree(result.getRoot());
                validateDependencyTree(result.getRoot(), projectGav,
                        unproductizedDependencies, unproductizedGavs);
            }

            // Write CSV report (before reportResults, which may throw).
            writeCsvReport(unproductizedGavs);

            // Report results.
            getLog().info("=== Project Dependency Verification Results ===");
            reportResults(unproductizedDependencies, resolutionErrors);

        } catch (MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Error verifying project dependencies", e);
        }
    }

    /**
     * Collects the transitive dependency tree for the current project using Maven's
     * Aether resolver. Uses the project's own artifact as the root and the project's
     * configured remote repositories.
     *
     * @return the collect result containing the dependency tree, or null on complete failure
     */
    private CollectResult collectDependencyTree() {
        try {
            String extension = "pom".equals(project.getPackaging()) ? "pom" : "jar";
            CollectRequest request = new CollectRequest();
            request.setRoot(new Dependency(
                    new DefaultArtifact(project.getGroupId(), project.getArtifactId(),
                            extension, project.getVersion()),
                    "compile"));
            request.setRepositories(remoteRepositories);

            return repositorySystem.collectDependencies(repoSession, request);

        } catch (DependencyCollectionException e) {
            getLog().warn("Dependency collection errors for "
                    + project.getGroupId() + ":" + project.getArtifactId()
                    + ": " + e.getMessage());
            // Return the partial result so we can still validate what was resolved.
            return e.getResult();
        }
    }

}
