package ru.curs.celesta.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;

@Mojo(
        name = "gen-test-score-resources",
        defaultPhase = LifecyclePhase.GENERATE_TEST_RESOURCES
)
public final class GenTestScoreResourcesMojo extends AbstractGenScoreResourcesMojo {

    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {
        getScorePaths = this::getTestScorePaths;
        generatedResourcesDirName = "generated-test-resources";

        super.execute();
    }

}
