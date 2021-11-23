package org.utplsql.maven.plugin;

import oracle.jdbc.pool.OracleDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.logging.MessageUtils;
import org.utplsql.api.DBHelper;
import org.utplsql.api.FileMapperOptions;
import org.utplsql.api.JavaApiVersionInfo;
import org.utplsql.api.KeyValuePair;
import org.utplsql.api.TestRunner;
import org.utplsql.api.Version;
import org.utplsql.api.db.DatabaseInformation;
import org.utplsql.api.db.DefaultDatabaseInformation;
import org.utplsql.api.exception.SomeTestsFailedException;
import org.utplsql.api.reporter.CoreReporters;
import org.utplsql.api.reporter.Reporter;
import org.utplsql.api.reporter.ReporterFactory;
import org.utplsql.maven.plugin.helper.PluginDefault;
import org.utplsql.maven.plugin.helper.SqlScannerHelper;
import org.utplsql.maven.plugin.model.ReporterParameter;
import org.utplsql.maven.plugin.reporter.ReporterWriter;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * This class expose the {@link TestRunner} interface to Maven.
 *
 * @author Alberto Hernández
 * @author Simon Martinelli
 */
@Mojo(name = "test", defaultPhase = LifecyclePhase.TEST)
public class UtPLSQLMojo extends AbstractMojo {

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

    @Parameter(property = "dbUrl")
    protected String url;

    @Parameter(property = "dbUser")
    protected String user;

    @Parameter(property = "dbPass")
    protected String password;

    @Parameter
    protected String includeObject;

    @Parameter
    protected String excludeObject;

    @Parameter(defaultValue = "false")
    protected boolean skipCompatibilityCheck;

    @Parameter
    protected final List<ReporterParameter> reporters = new ArrayList<>();

    @Parameter
    protected final List<String> paths = new ArrayList<>();

    /**
     * Sources Configuration
     */

    @Parameter
    protected final List<Resource> sources = new ArrayList<>();

    @Parameter
    private String sourcesOwner;

    @Parameter
    private String sourcesRegexExpression;

    @Parameter
    private Integer sourcesOwnerSubexpression;

    @Parameter
    private Integer sourcesNameSubexpression;

    @Parameter
    private Integer sourcesTypeSubexpression;

    @Parameter
    private List<CustomTypeMapping> sourcesCustomTypeMapping;

    /**
     * Tests Configuration
     */
    @Parameter
    protected final List<Resource> tests = new ArrayList<>();

    @Parameter
    private String testsOwner;

    @Parameter
    private String testsRegexExpression;

    @Parameter
    private Integer testsOwnerSubexpression;

    @Parameter
    private Integer testsNameSubexpression;

    @Parameter
    private Integer testsTypeSubexpression;

    @Parameter
    private List<CustomTypeMapping> testsCustomTypeMapping;

    @Parameter
    private final Set<String> tags = new LinkedHashSet<>();

    @Parameter
    private boolean randomTestOrder;

    @Parameter
    private Integer randomTestOrderSeed;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    protected String targetDir;

    @Parameter(defaultValue = "${maven.test.failure.ignore}")
    protected boolean ignoreFailure;

    @Parameter(property = "skipUtplsqlTests", defaultValue = "false")
    protected boolean skipUtplsqlTests;

    @Parameter
    protected boolean dbmsOutput;

    /**
     * Color in the console, bases on Maven logging configuration.
     */
    private final boolean colorConsole = MessageUtils.isColorEnabled();

    private ReporterWriter reporterWriter;

    private final DatabaseInformation databaseInformation = new DefaultDatabaseInformation();

    /**
     * Executes the plugin.
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (skipUtplsqlTests) {
            getLog().info("utPLSQLTests are skipped.");
        } else {
            getLog().debug("Java Api Version = " + JavaApiVersionInfo.getVersion());
            loadConfFromEnvironment();

            Connection connection = null;
            try {
                FileMapperOptions sourceMappingOptions = buildSourcesOptions();
                FileMapperOptions testMappingOptions = buildTestsOptions();
                OracleDataSource ds = new OracleDataSource();
                ds.setURL(url);
                ds.setUser(user);
                ds.setPassword(password);
                connection = ds.getConnection();

                Version utlVersion = this.databaseInformation.getUtPlsqlFrameworkVersion(connection);
                getLog().info("utPLSQL Version = " + utlVersion);

                List<Reporter> reporterList = initReporters(connection, utlVersion, ReporterFactory.createEmpty());

                logParameters(sourceMappingOptions, testMappingOptions, reporterList);

                if (dbmsOutput) {
                    DBHelper.enableDBMSOutput(connection);
                    getLog().info("Enabled dbms_output.");
                }

                TestRunner runner = new TestRunner()
                        .addPathList(paths)
                        .addReporterList(reporterList)
                        .sourceMappingOptions(sourceMappingOptions)
                        .testMappingOptions(testMappingOptions)
                        .skipCompatibilityCheck(skipCompatibilityCheck)
                        .colorConsole(colorConsole)
                        .addTags(tags)
                        .randomTestOrder(randomTestOrder)
                        .randomTestOrderSeed(randomTestOrderSeed)
                        .failOnErrors(!ignoreFailure);

                if (StringUtils.isNotBlank(excludeObject)) {
                    runner.excludeObject(excludeObject);
                }
                if (StringUtils.isNotBlank(includeObject)) {
                    runner.includeObject(includeObject);
                }

                runner.run(connection);

            } catch (SomeTestsFailedException e) {
                if (!this.ignoreFailure) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            } catch (SQLException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            } finally {
                try {
                    if (null != connection) {
                        reporterWriter.writeReporters(connection);

                        DBHelper.disableDBMSOutput(connection);
                        connection.close();
                    }
                } catch (Exception e) {
                    getLog().error(e.getMessage(), e);
                }
            }
        }
    }

    private void loadConfFromEnvironment() {
        if (StringUtils.isEmpty(url)) {
            url = System.getProperty("dbUrl");
        }
        if (StringUtils.isEmpty(user)) {
            user = System.getProperty("dbUser");
        }
        if (StringUtils.isEmpty(password)) {
            password = System.getProperty("dbPass");
        }
    }

    private FileMapperOptions buildSourcesOptions() throws MojoExecutionException {
        try {
            if (sources.isEmpty()) {
                File defaultSourceDirectory = new File(project.getBasedir(), PluginDefault.SOURCE_DIRECTORY);
                if (defaultSourceDirectory.exists()) {
                    sources.add(PluginDefault.buildDefaultSource());
                } else {
                    return new FileMapperOptions(new ArrayList<>());
                }
            }

            List<String> scripts = SqlScannerHelper.findSqlScripts(project.getBasedir(), sources,
                    PluginDefault.SOURCE_DIRECTORY, PluginDefault.SOURCE_FILE_PATTERN);
            return createFileMapperOptions(scripts, sourcesOwner, sourcesRegexExpression, sourcesOwnerSubexpression,
                    sourcesNameSubexpression, sourcesTypeSubexpression, sourcesCustomTypeMapping);

        } catch (Exception e) {
            throw new MojoExecutionException("Invalid <SOURCES> in your pom.xml", e);
        }

    }

    private FileMapperOptions buildTestsOptions() throws MojoExecutionException {
        try {
            if (tests.isEmpty()) {
                File defaultTestDirectory = new File(project.getBasedir(), PluginDefault.TEST_DIRECTORY);
                if (defaultTestDirectory.exists()) {
                    tests.add(PluginDefault.buildDefaultTest());
                } else {
                    return new FileMapperOptions(new ArrayList<>());
                }
            }

            List<String> scripts = SqlScannerHelper.findSqlScripts(project.getBasedir(), tests, PluginDefault.TEST_DIRECTORY,
                    PluginDefault.TEST_FILE_PATTERN);
            return createFileMapperOptions(scripts, testsOwner, testsRegexExpression, testsOwnerSubexpression,
                    testsNameSubexpression, testsTypeSubexpression, testsCustomTypeMapping);

        } catch (Exception e) {
            throw new MojoExecutionException("Invalid <TESTS> in your pom.xml: " + e.getMessage());
        }

    }

    private FileMapperOptions createFileMapperOptions(List<String> scripts, String objectOwner, String regexPattern,
                                                      Integer ownerSubExpression, Integer nameSubExpression,
                                                      Integer typeSubExpression, List<CustomTypeMapping> typeMappings) {
        FileMapperOptions fileMapperOptions = new FileMapperOptions(scripts);

        if (StringUtils.isNotEmpty(objectOwner)) {
            fileMapperOptions.setObjectOwner(objectOwner);
        }

        if (StringUtils.isNotEmpty(regexPattern)) {
            fileMapperOptions.setRegexPattern(regexPattern);
        }

        if (ownerSubExpression != null) {
            fileMapperOptions.setOwnerSubExpression(ownerSubExpression);
        }

        if (nameSubExpression != null) {
            fileMapperOptions.setNameSubExpression(nameSubExpression);
        }

        if (typeSubExpression != null) {
            fileMapperOptions.setTypeSubExpression(typeSubExpression);
        }

        if (typeMappings != null && !typeMappings.isEmpty()) {
            fileMapperOptions.setTypeMappings(new ArrayList<>());
            for (CustomTypeMapping typeMapping : typeMappings) {
                fileMapperOptions.getTypeMappings()
                        .add(new KeyValuePair(typeMapping.getCustomMapping(), typeMapping.getType()));
            }
        }

        return fileMapperOptions;
    }

    private List<Reporter> initReporters(Connection connection, Version utlVersion, ReporterFactory reporterFactory)
            throws SQLException {

        List<Reporter> reporterList = new ArrayList<>();
        reporterWriter = new ReporterWriter(targetDir, utlVersion, getLog());

        if (reporters.isEmpty()) {
            ReporterParameter reporterParameter = new ReporterParameter();
            reporterParameter.setConsoleOutput(true);
            reporterParameter.setName(CoreReporters.UT_DOCUMENTATION_REPORTER.name());
            reporters.add(reporterParameter);
        }

        for (ReporterParameter reporterParameter : reporters) {
            Reporter reporter = reporterFactory.createReporter(reporterParameter.getName());
            reporter.init(connection);
            reporterList.add(reporter);

            // Turns the console output on by default if both file and console output are empty.
            if (!reporterParameter.isFileOutput() && null == reporterParameter.getConsoleOutput()) {
                reporterParameter.setConsoleOutput(true);
            }

            // Only added the reporter if at least one of the output is required
            if (StringUtils.isNotBlank(reporterParameter.getFileOutput()) || reporterParameter.isConsoleOutput()) {
                reporterWriter.addReporter(reporterParameter, reporter);
            }
        }

        return reporterList;
    }

    private void logParameters(FileMapperOptions sourceMappingOptions, FileMapperOptions testMappingOptions, List<Reporter> reporterList) {
        Log log = getLog();
        log.info("Invoking TestRunner with: " + targetDir);

        if (!log.isDebugEnabled()) {
            return;
        }

        log.debug("Invoking TestRunner with: ");

        log.debug("reporters=");
        reporterList.forEach((Reporter r) -> log.debug(r.getTypeName()));

        log.debug("sources=");
        sourceMappingOptions.getFilePaths().forEach(log::debug);

        log.debug("tests=");
        testMappingOptions.getFilePaths().forEach(log::debug);
    }
}
