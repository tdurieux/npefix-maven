package com.github.tdurieux.npefix.maven.plugin;

import fr.inria.spirals.npefix.main.all.Launcher;
import fr.inria.spirals.npefix.resi.CallChecker;
import fr.inria.spirals.npefix.resi.context.Decision;
import fr.inria.spirals.npefix.resi.context.NPEOutput;
import fr.inria.spirals.npefix.resi.strategies.NoStrat;
import fr.inria.spirals.npefix.resi.strategies.ReturnType;
import fr.inria.spirals.npefix.resi.strategies.Strat1A;
import fr.inria.spirals.npefix.resi.strategies.Strat1B;
import fr.inria.spirals.npefix.resi.strategies.Strat2A;
import fr.inria.spirals.npefix.resi.strategies.Strat2B;
import fr.inria.spirals.npefix.resi.strategies.Strat3;
import fr.inria.spirals.npefix.resi.strategies.Strat4;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.surefire.log.api.NullConsoleLogger;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.surefire.report.ReportTestCase;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.MavenReportException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Mojo( name = "npefix",
        defaultPhase = LifecyclePhase.TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
//@Execute( lifecycle = "surefire", phase = LifecyclePhase.TEST )
public class NPEFixMojo extends AbstractMojo {
    /**
     * Location of the file.
     */
    @Parameter( defaultValue = "${project.build.directory}/npefix", property = "outputDir", required = true )
    private File outputDirectory;

    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;

    @Parameter( defaultValue = "${reactorProjects}", readonly = true )
    private List<MavenProject> reactorProjects;

    @Parameter( defaultValue = "false", property = "aggregate" )
    private boolean aggregate;

    public void execute() throws MojoExecutionException {
        List<String> npeTests = getNPETest();
        if (npeTests.isEmpty()) {
            throw new RuntimeException("No failing test with NullPointerException");
        }

        final List<String> dependencies = getClasspath();
        List<String> sourceFolders = getSourceFolders();
        List<String> testFolders = getTestFolders();

        final String[] sources = new String[sourceFolders.size() + testFolders.size()];
        int indexSource = 0;

        for (int i = 0; i < testFolders.size(); i++, indexSource++) {
            String s = testFolders.get(i);
            sources[indexSource] = s;
        }
        for (int i = 0; i < sourceFolders.size(); i++, indexSource++) {
            String s = sourceFolders.get(i);
            sources[indexSource] = s;
        }
        dependencies.add(outputDirectory.getAbsolutePath() + "/npefix-bin");

        Launcher  npefix = new Launcher(sources, outputDirectory.getAbsolutePath() + "/npefix-output", outputDirectory.getAbsolutePath() + "/npefix-bin", classpath(dependencies));

        npefix.instrument();

        NPEOutput lapses = npefix.runStrategy(npeTests, new NoStrat(),
                new Strat1A(),
                new Strat1B(),
                new Strat2A(),
                new Strat2B(),
                new Strat3(),
                new Strat4(ReturnType.NULL),
                new Strat4(ReturnType.VAR),
                new Strat4(ReturnType.NEW),
                new Strat4(ReturnType.VOID));


        spoon.Launcher spoon = new spoon.Launcher();
        for (String s : sourceFolders) {
            spoon.addInputResource(s);
        }

        spoon.getModelBuilder().setSourceClasspath(dependencies.toArray(new String[0]));
        spoon.buildModel();

        JSONObject jsonObject = lapses.toJSON(spoon);
        jsonObject.put("endInit", new Date().getTime());
        try {
            for (Decision decision : CallChecker.strategySelector.getSearchSpace()) {
                jsonObject.append("searchSpace", decision.toJSON());
            }
            jsonObject.write(new FileWriter(outputDirectory.getAbsolutePath() + "/patches.json"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String classpath(List<String> dependencies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dependencies.size(); i++) {
            String s = dependencies.get(i);
            if (new File(s).exists()) {
                sb.append(s).append(File.pathSeparatorChar);
            }
        }
        sb.append("/home/thomas/.m2/repository/fr/inria/spirals/npefix/0.4-SNAPSHOT/npefix-0.4-SNAPSHOT.jar").append(File.pathSeparatorChar);
        System.out.println(sb);
        return sb.toString();
    }

    private List<String> getClasspath() {
        List<String> classpath = new ArrayList<String>();
        for (MavenProject mavenProject : reactorProjects) {
            try {
                classpath.addAll(mavenProject.getTestClasspathElements());
                classpath.removeAll(mavenProject.getSystemClasspathElements());
            } catch (DependencyResolutionRequiredException e) {
                continue;
            }
        }
        for (int i = 0; i < classpath.size(); i++) {
            String s = classpath.get(i);
            if (s.endsWith("test-classes")) {
                classpath.remove(s);
            }
        }

        return classpath;
    }

    private List<String> getSourceFolders() {
        List<String> sourceFolder = new ArrayList<String>();
        for (MavenProject mavenProject : reactorProjects) {
            sourceFolder.add(mavenProject.getBuild().getSourceDirectory());
        }
        return sourceFolder;
    }

    private List<String> getTestFolders() {
        List<String> sourceFolder = new ArrayList<String>();
        for (MavenProject mavenProject : reactorProjects) {
            sourceFolder.add(mavenProject.getBuild().getTestSourceDirectory());
        }
        return sourceFolder;
    }

    private List<File> getReportsDirectories() {
        List<File> resolvedReportsDirectories = new ArrayList<File>();
        if ( aggregate ) {
            if ( !project.isExecutionRoot() ) {
                return null;
            }
            for (MavenProject mavenProject : getProjectsWithoutRoot()) {
                resolvedReportsDirectories.add(getSurefireReportsDirectory(mavenProject));
            }
        } else {
            resolvedReportsDirectories.add(getSurefireReportsDirectory(project));
        }
        return resolvedReportsDirectories;
    }

    private List<MavenProject> getProjectsWithoutRoot() {
        List<MavenProject> result = new ArrayList<MavenProject>();
        for ( MavenProject subProject : reactorProjects ) {
            if (!project.equals(subProject)) {
                result.add(subProject);
            }
        }
        return result;
    }

    private File getSurefireReportsDirectory( MavenProject subProject ) {
        String buildDir = subProject.getBuild().getDirectory();
        return new File( buildDir + "/surefire-reports" );
    }

    private List<String> getNPETest() {
        List<String> output = new ArrayList<>();

        List<File> reportsDirectories = getReportsDirectories();
        SurefireReportParser parser = new SurefireReportParser(reportsDirectories, Locale.ENGLISH, new NullConsoleLogger());
        try {
            List<ReportTestSuite> testSuites = parser.parseXMLReportFiles();
            for (int i = 0; i < testSuites.size(); i++) {
                ReportTestSuite reportTestSuite = testSuites.get(i);
                List<ReportTestCase> testCases = reportTestSuite.getTestCases();
                for (int j = 0; j < testCases.size(); j++) {
                    ReportTestCase reportTestCase = testCases.get(j);
                    if (reportTestCase.hasFailure() && reportTestCase.getFailureDetail().contains("NullPointerException")) {
                        output.add(reportTestCase.getFullClassName() + "#" + reportTestCase.getName());
                    }
                }
            }
        } catch (MavenReportException e) {
            e.printStackTrace();
        }
        return output;
    }
}
