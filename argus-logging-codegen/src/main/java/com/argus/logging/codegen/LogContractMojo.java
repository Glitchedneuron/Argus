package com.argus.logging.codegen;

import com.argus.logging.codegen.model.LogContract;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class LogContractMojo extends AbstractMojo {

    @Parameter(property = "argus.contractFile",
               defaultValue = "${project.basedir}/contracts/log-contract.yaml",
               required = true)
    private File contractFile;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/argus-logging")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (!contractFile.exists()) {
            throw new MojoExecutionException("Contract file not found: " + contractFile);
        }
        try {
            LogContract contract = new ContractParser().parse(contractFile);
            new JavaCodeGenerator(getLog()).generate(contract, outputDirectory);
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        } catch (Exception e) {
            throw new MojoExecutionException("Code generation failed: " + e.getMessage(), e);
        }
    }
}
