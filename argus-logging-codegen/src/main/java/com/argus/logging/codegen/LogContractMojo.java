package com.argus.logging.codegen;

import com.argus.logging.codegen.model.LogContract;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;

/**
 * Reads {@code log-contract.yaml} and generates Java 21 records + enum classes
 * into {@code target/generated-sources/argus-logging}, then registers that
 * directory as a compile source root.
 *
 * <h2>Usage in a service POM</h2>
 * <pre>{@code
 * <plugin>
 *   <groupId>com.argus</groupId>
 *   <artifactId>argus-logging-codegen</artifactId>
 *   <version>1.0.0-SNAPSHOT</version>
 *   <executions>
 *     <execution>
 *       <goals><goal>generate</goal></goals>
 *       <configuration>
 *         <contractFile>${project.basedir}/../contracts/log-contract.yaml</contractFile>
 *       </configuration>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 *
 * <p>The generated sources are compiled together with the rest of the service code.
 * The {@code argus-logging-core} runtime jar is needed at runtime.</p>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class LogContractMojo extends AbstractMojo {

    /**
     * Path to the YAML contract file.
     * Defaults to {@code ${project.basedir}/contracts/log-contract.yaml}.
     */
    @Parameter(
            property = "argus.contractFile",
            defaultValue = "${project.basedir}/contracts/log-contract.yaml",
            required = true)
    private File contractFile;

    /**
     * Directory where generated Java sources are written.
     * Added to the project's compile source roots automatically.
     */
    @Parameter(
            property = "argus.outputDirectory",
            defaultValue = "${project.build.directory}/generated-sources/argus-logging")
    private File outputDirectory;

    /** Whether to skip code generation entirely. */
    @Parameter(property = "argus.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("[argus-logging-codegen] skipped (argus.skip=true)");
            return;
        }

        if (!contractFile.exists()) {
            throw new MojoExecutionException(
                    "Contract file not found: " + contractFile.getAbsolutePath()
                    + "\nSet <contractFile> in the plugin configuration or create the file.");
        }

        getLog().info("[argus-logging-codegen] contract : " + contractFile.getAbsolutePath());
        getLog().info("[argus-logging-codegen] output   : " + outputDirectory.getAbsolutePath());

        try {
            LogContract contract = new ContractParser().parse(contractFile);
            new JavaCodeGenerator(getLog()).generate(contract, outputDirectory);
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            getLog().info("[argus-logging-codegen] done — "
                    + contract.getEnums().size() + " enum(s), "
                    + contract.getEvents().size() + " event record(s)");
        } catch (Exception e) {
            throw new MojoExecutionException(
                    "Code generation from contract failed: " + e.getMessage(), e);
        }
    }
}
