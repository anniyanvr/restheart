/*-
 * ========================LICENSE_START=================================
 * restheart-core
 * %%
 * Copyright (C) 2014 - 2020 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheNotFoundException;
import com.jayway.jsonpath.spi.json.GsonJsonProvider;
import com.jayway.jsonpath.spi.json.JsonProvider;
import com.jayway.jsonpath.spi.mapper.GsonMappingProvider;
import com.jayway.jsonpath.spi.mapper.MappingProvider;
import static com.sun.akuma.CLibrary.LIBC;
import static io.undertow.Handlers.resource;
import io.undertow.Undertow;
import io.undertow.Undertow.Builder;
import io.undertow.UndertowOptions;
import io.undertow.server.handlers.AllowedMethodsHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.HttpContinueAcceptingHandler;
import io.undertow.server.handlers.RequestLimit;
import io.undertow.server.handlers.RequestLimitingHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.HttpString;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import static org.fusesource.jansi.Ansi.Color.GREEN;
import static org.fusesource.jansi.Ansi.Color.MAGENTA;
import static org.fusesource.jansi.Ansi.Color.RED;
import static org.fusesource.jansi.Ansi.ansi;
import org.fusesource.jansi.AnsiConsole;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_EMBEDDED_KEY;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_WELCOME_FILE_KEY;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_WHAT_KEY;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_WHERE_KEY;
import org.restheart.exchange.Exchange;
import static org.restheart.exchange.Exchange.MAX_CONTENT_SIZE;
import org.restheart.exchange.ExchangeKeys;
import org.restheart.exchange.PipelineInfo;
import static org.restheart.exchange.PipelineInfo.PIPELINE_TYPE.PROXY;
import static org.restheart.exchange.PipelineInfo.PIPELINE_TYPE.SERVICE;
import static org.restheart.exchange.PipelineInfo.PIPELINE_TYPE.STATIC_RESOURCE;
import org.restheart.handlers.CORSHandler;
import org.restheart.handlers.ConfigurableEncodingHandler;
import org.restheart.handlers.ErrorHandler;
import org.restheart.handlers.PipelinedHandler;
import static org.restheart.handlers.PipelinedHandler.pipe;
import org.restheart.handlers.PipelinedWrappingHandler;
import org.restheart.handlers.QueryStringRebuilder;
import org.restheart.handlers.RequestInterceptorsExecutor;
import org.restheart.handlers.RequestLogger;
import org.restheart.handlers.RequestNotManagedHandler;
import org.restheart.handlers.ResponseInterceptorsExecutor;
import org.restheart.handlers.ResponseSender;
import org.restheart.handlers.ServiceExchangeInitializer;
import org.restheart.handlers.TracingInstrumentationHandler;
import org.restheart.handlers.injectors.AuthHeadersRemover;
import org.restheart.handlers.injectors.ConduitInjector;
import org.restheart.handlers.injectors.PipelineInfoInjector;
import org.restheart.handlers.injectors.RequestContentInjector;
import static org.restheart.handlers.injectors.RequestContentInjector.Policy.ON_REQUIRES_CONTENT_AFTER_AUTH;
import static org.restheart.handlers.injectors.RequestContentInjector.Policy.ON_REQUIRES_CONTENT_BEFORE_AUTH;
import org.restheart.handlers.injectors.XForwardedHeadersInjector;
import org.restheart.handlers.injectors.XPoweredByInjector;
import static org.restheart.plugins.InitPoint.AFTER_STARTUP;
import static org.restheart.plugins.InitPoint.BEFORE_STARTUP;
import static org.restheart.plugins.InterceptPoint.REQUEST_AFTER_AUTH;
import static org.restheart.plugins.InterceptPoint.REQUEST_BEFORE_AUTH;
import org.restheart.plugins.PluginRecord;
import org.restheart.plugins.PluginsRegistryImpl;
import org.restheart.plugins.security.AuthMechanism;
import org.restheart.plugins.security.Authorizer;
import org.restheart.plugins.security.TokenManager;
import org.restheart.security.handlers.SecurityHandler;
import org.restheart.security.plugins.authorizers.FullAuthorizer;
import org.restheart.utils.FileUtils;
import org.restheart.utils.LoggingInitializer;
import org.restheart.utils.OSChecker;
import static org.restheart.utils.PluginUtils.defaultURI;
import static org.restheart.utils.PluginUtils.initPoint;
import org.restheart.utils.RESTHeartDaemon;
import org.restheart.utils.ResourcesExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.SslClientAuthMode;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;
import org.yaml.snakeyaml.Yaml;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class Bootstrapper {

    private static final Logger LOGGER = LoggerFactory
            .getLogger(Bootstrapper.class);

    private static boolean IS_FORKED;

    private static final Map<String, File> TMP_EXTRACTED_FILES = new HashMap<>();

    private static Path CONFIGURATION_FILE;
    private static Path PROPERTIES_FILE;

    private static GracefulShutdownHandler HANDLERS = null;
    private static Configuration configuration;
    private static Undertow undertowServer;

    private static final String EXITING = ", exiting...";
    private static final String INSTANCE = " instance ";
    private static final String STARTING = "Starting ";
    private static final String UNDEFINED = "undefined";
    private static final String RESTHEART = "RESTHeart";
    private static final String VERSION = "Version {}";

    /**
     * getConfiguration
     *
     * @return the global configuration
     */
    public static Configuration getConfiguration() {
        return configuration;
    }

    private static void parseCommandLineParameters(final String[] args) {
        Args parameters = new Args();
        JCommander cmd = JCommander.newBuilder().addObject(parameters).build();
        cmd.setProgramName("java -Dfile.encoding=UTF-8 -jar -server restheart.jar");
        try {
            cmd.parse(args);
            if (parameters.help) {
                cmd.usage();
                System.exit(0);
            }

            String confFilePath = (parameters.configPath == null)
                    ? System.getenv("RESTHEART__CONFFILE")
                    : parameters.configPath;
            CONFIGURATION_FILE = FileUtils.getFileAbsolutePath(confFilePath);

            FileUtils.getFileAbsolutePath(parameters.configPath);

            IS_FORKED = parameters.isForked;
            String propFilePath = (parameters.envFile == null)
                    ? System.getenv("RESTHEART_ENVFILE")
                    : parameters.envFile;

            PROPERTIES_FILE = FileUtils.getFileAbsolutePath(propFilePath);
        } catch (com.beust.jcommander.ParameterException ex) {
            LOGGER.error(ex.getMessage());
            cmd.usage();
            System.exit(1);
        }
    }

    public static void main(final String[] args) throws ConfigurationException, IOException {
        parseCommandLineParameters(args);
        setJsonpathDefaults();
        configuration = loadConfiguration();
        run();
    }

    /**
     * Configuration from JsonPath
     */
    private static void setJsonpathDefaults() {
        com.jayway.jsonpath.Configuration.setDefaults(
                new com.jayway.jsonpath.Configuration.Defaults() {
            private final JsonProvider jsonProvider = new GsonJsonProvider();
            private final MappingProvider mappingProvider = new GsonMappingProvider();

            @Override
            public JsonProvider jsonProvider() {
                return jsonProvider;
            }

            @Override
            public MappingProvider mappingProvider() {
                return mappingProvider;
            }

            @Override
            public Set<com.jayway.jsonpath.Option> options() {
                return EnumSet.noneOf(com.jayway.jsonpath.Option.class);
            }
        });
    }

    private static void run() {
        if (!configuration.isAnsiConsole()) {
            AnsiConsole.systemInstall();
        }

        if (!hasForkOption()) {
            initLogging(null);
            startServer(false);
        } else {
            if (OSChecker.isWindows()) {
                LOGGER.error("Fork is not supported on Windows");
                LOGGER.info(ansi().fg(GREEN).bold().a("RESTHeart stopped")
                        .reset().toString());
                System.exit(-1);
            }

            // RHSecDaemon only works on POSIX OSes
            final boolean isPosix = FileSystems.getDefault()
                    .supportedFileAttributeViews()
                    .contains("posix");

            if (!isPosix) {
                logErrorAndExit("Unable to fork process, "
                        + "this is only supported on POSIX compliant OSes",
                        null, false, -1);
            }

            RESTHeartDaemon d = new RESTHeartDaemon();
            if (d.isDaemonized()) {
                try {
                    d.init();
                    LOGGER.info("Forked process: {}", LIBC.getpid());
                    initLogging(d);
                } catch (Exception t) {
                    logErrorAndExit("Error staring forked process", t, false, false, -1);
                }
                startServer(true);
            } else {
                initLogging(d);
                try {
                    logWindowsStart();
                    logLoggingConfiguration(true);
                    logManifestInfo();
                    d.daemonize();
                } catch (Throwable t) {
                    logErrorAndExit("Error forking", t, false, false, -1);
                }
            }
        }
    }

    private static void logWindowsStart() {
        String version = Version.getInstance().getVersion() == null
                ? "Unknown, not packaged"
                : Version.getInstance().getVersion();

        String info = String.format("  {%n"
                + "    \"Version\": \"%s\",%n"
                + "    \"Instance-Name\": \"%s\",%n"
                + "    \"Configuration\": \"%s\",%n"
                + "    \"Environment\": \"%s\",%n"
                + "    \"Build-Time\": \"%s\"%n"
                + "  }",
                ansi().fg(MAGENTA).a(version).reset().toString(),
                ansi().fg(MAGENTA).a(getInstanceName()).reset().toString(),
                ansi().fg(MAGENTA).a(CONFIGURATION_FILE).reset().toString(),
                ansi().fg(MAGENTA).a(PROPERTIES_FILE).reset().toString(),
                ansi().fg(MAGENTA).a(Version.getInstance().getBuildTime()).reset().toString());

        LOGGER.info("Starting {}\n{}", ansi().fg(RED).a(RESTHEART).reset().toString(), info);
    }

    private static void logManifestInfo() {
        if (LOGGER.isDebugEnabled()) {
            final Set<Map.Entry<Object, Object>> MANIFEST_ENTRIES = FileUtils.findManifestInfo();

            if (MANIFEST_ENTRIES != null) {
                LOGGER.debug("Build Information: {}", MANIFEST_ENTRIES.toString());
            } else {
                LOGGER.debug("Build Information: {}", "Unknown, not packaged");
            }
        }
    }

    private static Configuration loadConfiguration() throws ConfigurationException, UnsupportedEncodingException {
        if (CONFIGURATION_FILE == null) {
            LOGGER.warn("No configuration file provided, starting with default values!");
            return new Configuration();
        } else if (PROPERTIES_FILE == null) {
            try {
                if (Configuration.isParametric(CONFIGURATION_FILE)) {
                    logErrorAndExit("Configuration is parametric but no properties file has been specified."
                            + " You can use -e option to specify the properties file. "
                            + "For more information check https://restheart.org/docs/configuration",
                            null, false, -1);
                }
            } catch (IOException ioe) {
                logErrorAndExit("Configuration file not found " + CONFIGURATION_FILE, null, false, -1);
            }

            return new Configuration(CONFIGURATION_FILE, false);
        } else {
            final Properties p = new Properties();
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(PROPERTIES_FILE.toFile()), "UTF-8")) {
                p.load(reader);
            } catch (FileNotFoundException fnfe) {
                logErrorAndExit("Properties file not found " + PROPERTIES_FILE, null, false, -1);
            } catch (IOException ieo) {
                logErrorAndExit("Error reading properties file " + PROPERTIES_FILE, null, false, -1);
            }

            final StringWriter writer = new StringWriter();
            try (BufferedReader reader = new BufferedReader(new FileReader(CONFIGURATION_FILE.toFile()))) {
                Mustache m = new DefaultMustacheFactory().compile(reader, "configuration-file");
                m.execute(writer, p);
                writer.flush();
            } catch (MustacheNotFoundException ex) {
                logErrorAndExit("Configuration file not found: " + CONFIGURATION_FILE, ex, false, -1);
            } catch (FileNotFoundException fnfe) {
                logErrorAndExit("Configuration file not found " + CONFIGURATION_FILE, null, false, -1);
            } catch (IOException ieo) {
                logErrorAndExit("Error reading configuration file " + CONFIGURATION_FILE, null, false, -1);
            }

            Map<String, Object> obj = new Yaml().load(writer.toString());
            return new Configuration(obj, false);
        }
    }

    private static void logStartMessages() {
        String instanceName = getInstanceName();
        LOGGER.info(STARTING + ansi().fg(RED).bold().a(RESTHEART).reset().toString()
                + INSTANCE
                + ansi().fg(RED).bold().a(instanceName).reset().toString());
        LOGGER.info(VERSION, Configuration.VERSION);
        LOGGER.debug("Configuration = " + configuration.toString());
    }

    /**
     * logs warning message if pid file exists
     *
     * @param confFilePath
     * @param propFilePath
     * @return true if pid file exists
     */
    private static boolean checkPidFile(Path confFilePath, Path propFilePath) {
        if (OSChecker.isWindows()) {
            return false;
        }

        // pid file name include the hash of the configuration file so that
        // for each configuration we can have just one instance running
        Path pidFilePath = FileUtils
                .getPidFilePath(FileUtils.getFileAbsolutePathHash(confFilePath, propFilePath));
        if (Files.exists(pidFilePath)) {
            LOGGER.warn("Found pid file! If this instance is already "
                    + "running, startup will fail with a BindException");
            return true;
        }
        return false;
    }

    /**
     * Shutdown the server
     *
     * @param args command line arguments
     */
    public static void shutdown(final String[] args) {
        stopServer(false);
    }

    /**
     * initLogging
     *
     * @param args
     * @param d
     */
    private static void initLogging(final RESTHeartDaemon d) {
        LoggingInitializer.setLogLevel(configuration.getLogLevel());
        if (d != null && d.isDaemonized()) {
            LoggingInitializer.stopConsoleLogging();
            LoggingInitializer.startFileLogging(configuration
                    .getLogFilePath());
        } else if (!hasForkOption()) {
            if (!configuration.isLogToConsole()) {
                LoggingInitializer.stopConsoleLogging();
            }
            if (configuration.isLogToFile()) {
                LoggingInitializer.startFileLogging(configuration
                        .getLogFilePath());
            }
        }
    }

    /**
     * logLoggingConfiguration
     *
     * @param fork
     */
    private static void logLoggingConfiguration(boolean fork) {
        String logbackConfigurationFile = System
                .getProperty("logback.configurationFile");

        boolean usesLogback = logbackConfigurationFile != null
                && !logbackConfigurationFile.isEmpty();

        if (usesLogback) {
            return;
        }

        if (configuration.isLogToFile()) {
            LOGGER.info("Logging to file {} with level {}",
                    configuration.getLogFilePath(),
                    configuration.getLogLevel());
        }

        if (!fork) {
            if (!configuration.isLogToConsole()) {
                LOGGER.info("Stop logging to console ");
            } else {
                LOGGER.info("Logging to console with level {}",
                        configuration.getLogLevel());
            }
        }
    }

    /**
     * hasForkOption
     *
     * @param args
     * @return true if has fork option
     */
    private static boolean hasForkOption() {
        return IS_FORKED;
    }

    /**
     * startServer
     *
     * @param fork
     */
    private static void startServer(boolean fork) {
        logStartMessages();

        Path pidFilePath = FileUtils.getPidFilePath(
                FileUtils.getFileAbsolutePathHash(CONFIGURATION_FILE, PROPERTIES_FILE));
        boolean pidFileAlreadyExists = false;

        if (!OSChecker.isWindows() && pidFilePath != null) {
            pidFileAlreadyExists = checkPidFile(CONFIGURATION_FILE, PROPERTIES_FILE);
        }

        logLoggingConfiguration(fork);
        logManifestInfo();

        // re-read configuration file, to log errors new that logger is initialized
        try {
            loadConfiguration();
        } catch (ConfigurationException | IOException ex) {
            logErrorAndExit(ex.getMessage() + EXITING, ex, false, -1);
        }

        // force instantiation of all plugins singletons
        try {
            PluginsRegistryImpl.getInstance().instantiateAll();
        } catch (IllegalArgumentException iae) {
            // this occurs instatiating plugin missing external dependencies
            // unfortunatly Classgraph wraps it to IllegalArgumentException

            if (iae.getMessage() != null
                    && iae.getMessage().startsWith("Could not load class")) {

                logErrorAndExit("Error instantiating plugins: "
                        + "an external dependency is missing. "
                        + "Copy the missing dependency jar to the plugins directory to add it to the classpath",
                        iae, false, -112);
            } else {
                logErrorAndExit("Error instantiating plugins", iae, false, -110);
            }
        } catch (NoClassDefFoundError ncdfe) {
            // this occurs instatiating plugin missing external dependencies
            // unfortunatly Classgraph wraps it to IllegalArgumentException

                logErrorAndExit("Error instantiating plugins: "
                        + "an external dependency is missing. "
                        + "Copy the missing dependency jar to the plugins directory to add it to the classpath",
                        ncdfe, false, -112);
        }catch (LinkageError le) {
            // this occurs executing plugin code compiled
            // with wrong version of restheart-commons

            String version = Version.getInstance().getVersion() == null
                    ? "of correct version"
                    : "v" + Version.getInstance().getVersion();

            logErrorAndExit("Linkage error instantiating plugins "
                    + "Check that all plugins were compiled against restheart-commons "
                    + version, le, false, -111);
        } catch (Throwable t) {
            logErrorAndExit("Error instantiating plugins", t, false, -110);
        }

        // run pre startup initializers
        PluginsRegistryImpl.getInstance()
                .getInitializers()
                .stream()
                .filter(i -> initPoint(i.getInstance()) == BEFORE_STARTUP)
                .forEach(i -> {
                    try {
                        i.getInstance().init();
                    } catch (NoClassDefFoundError iae) {
                        // this occurs executing interceptors missing external dependencies

                        LOGGER.error("Error executing initializer {} "
                                + "An external dependency is missing. "
                                + "Copy the missing dependency jar to the plugins directory to add it to the classpath",
                                i.getName(), iae);
                    } catch (LinkageError le) {
                        // this occurs executing plugin code compiled
                        // with wrong version of restheart-commons

                        String version = Version.getInstance().getVersion() == null
                                ? "of correct version"
                                : "v" + Version.getInstance().getVersion();

                        LOGGER.error("Linkage error executing initializer {} "
                                + "Check that it was compiled against restheart-commons {}",
                                i.getName(), version, le);
                    } catch (Throwable t) {
                        LOGGER.error("Error executing initializer {}", i.getName());
                    }
                });
        try {
            startCoreSystem();
        } catch (Throwable t) {
            logErrorAndExit("Error starting RESTHeart. Exiting...",
                    t,
                    false,
                    !pidFileAlreadyExists, -2);
        }

        Runtime.getRuntime()
                .addShutdownHook(new Thread() {
                    @Override
                    public void run() {
                        stopServer(false);
                    }
                }
                );

        // create pid file on supported OSes
        if (!OSChecker.isWindows()
                && pidFilePath != null) {
            FileUtils.createPidFile(pidFilePath);
        }

        // log pid file path on supported OSes
        if (!OSChecker.isWindows()
                && pidFilePath != null) {
            LOGGER.info("Pid file {}", pidFilePath);
        }

        // run initializers
        PluginsRegistryImpl.getInstance()
                .getInitializers()
                .stream()
                .filter(i -> initPoint(i.getInstance()) == AFTER_STARTUP)
                .forEach(i -> {
                    try {
                        i.getInstance().init();
                    } catch (NoClassDefFoundError iae) {
                        // this occurs executing interceptors missing external dependencies

                        LOGGER.error("Error executing initializer {} "
                                + "An external dependency is missing. "
                                + "Copy the missing dependency jar to the plugins directory to add it to the classpath",
                                i.getName(), iae);
                    } catch (LinkageError le) {
                        // this occurs executing plugin code compiled
                        // with wrong version of restheart-commons

                        String version = Version.getInstance().getVersion() == null
                                ? "of correct version"
                                : "v" + Version.getInstance().getVersion();

                        LOGGER.error("Linkage error executing initializer {} "
                                + "Check that it was compiled against restheart-commons {}",
                                i.getName(), version, le);
                    } catch (Throwable t) {
                        LOGGER.error("Error executing initializer {}",
                                i.getName(),
                                t);
                    }
                });

        LOGGER.info(ansi().fg(GREEN).bold().a("RESTHeart started").reset().toString());
    }

    private static String getInstanceName() {
        return configuration == null ? UNDEFINED
                : configuration.getInstanceName() == null
                ? UNDEFINED
                : configuration.getInstanceName();
    }

    /**
     * stopServer
     *
     * @param silent
     */
    private static void stopServer(boolean silent) {
        stopServer(silent, true);
    }

    /**
     * stopServer
     *
     * @param silent
     * @param removePid
     */
    private static void stopServer(boolean silent, boolean removePid) {
        if (!silent) {
            LOGGER.info("Stopping RESTHeart...");
        }

        if (HANDLERS != null) {
            if (!silent) {
                LOGGER.info("Waiting for pending request "
                        + "to complete (up to 1 minute)...");
            }
            try {
                HANDLERS.shutdown();
                HANDLERS.awaitShutdown(60 * 1000); // up to 1 minute
            } catch (InterruptedException ie) {
                LOGGER.error("Error while waiting for pending request "
                        + "to complete", ie);
                Thread.currentThread().interrupt();
            }
        }

        Path pidFilePath = FileUtils.getPidFilePath(FileUtils
                .getFileAbsolutePathHash(CONFIGURATION_FILE, PROPERTIES_FILE));

        if (removePid && pidFilePath != null) {
            if (!silent) {
                LOGGER.info("Removing the pid file {}",
                        pidFilePath.toString());
            }
            try {
                Files.deleteIfExists(pidFilePath);
            } catch (IOException ex) {
                LOGGER.error("Can't delete pid file {}",
                        pidFilePath.toString(), ex);
            }
        }

        if (!silent) {
            LOGGER.info("Cleaning up temporary directories...");
        }
        TMP_EXTRACTED_FILES.keySet().forEach(k -> {
            try {
                ResourcesExtractor.deleteTempDir(Bootstrapper.class, 
                        k, 
                        TMP_EXTRACTED_FILES.get(k));
            } catch (URISyntaxException | IOException ex) {
                LOGGER.error("Error cleaning up temporary directory {}",
                        TMP_EXTRACTED_FILES.get(k).toString(), ex);
            }
        });

        if (undertowServer != null) {
            undertowServer.stop();
        }

        if (!silent) {
            LOGGER.info(ansi().fg(GREEN).bold().a("RESTHeart stopped")
                    .reset().toString());
        }

        LoggingInitializer.stopLogging();
    }

    /**
     * startCoreSystem
     */
    private static void startCoreSystem() {
        if (configuration == null) {
            logErrorAndExit("No configuration found. exiting..", null, false, -1);
        }

        if (!configuration.isHttpsListener()
                && !configuration.isHttpListener()
                && !configuration.isAjpListener()) {
            logErrorAndExit("No listener specified. exiting..", null, false, -1);
        }

        final var tokenManager = PluginsRegistryImpl.getInstance()
                .getTokenManager();

        final var authMechanisms = PluginsRegistryImpl
                .getInstance()
                .getAuthMechanisms();

        if (authMechanisms == null || authMechanisms.isEmpty()) {
            LOGGER.warn(ansi().fg(RED).bold()
                    .a("No Authentication Mechanisms defined")
                    .reset().toString());
        }

        final var authorizers = PluginsRegistryImpl
                .getInstance()
                .getAuthorizers();

        if (authorizers == null || authorizers.isEmpty()) {
            LOGGER.warn(ansi().fg(RED).bold()
                    .a("No Authorizers defined")
                    .reset().toString());
        }

        SSLContext sslContext = null;

        try {
            sslContext = SSLContext.getInstance("TLS");

            KeyManagerFactory kmf = KeyManagerFactory
                    .getInstance(KeyManagerFactory.getDefaultAlgorithm());
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());

            if (getConfiguration().isUseEmbeddedKeystore()) {
                char[] storepass = "uiamuiam".toCharArray();
                char[] keypass = "uiamuiam".toCharArray();

                String storename = "sskeystore.jks";

                ks.load(Bootstrapper.class
                        .getClassLoader()
                        .getResourceAsStream(storename), storepass);
                kmf.init(ks, keypass);
            } else if (configuration.getKeystoreFile() != null
                    && configuration.getKeystorePassword() != null
                    && configuration.getCertPassword() != null) {
                try (FileInputStream fis = new FileInputStream(
                        new File(configuration.getKeystoreFile()))) {
                    ks.load(fis, configuration.getKeystorePassword().toCharArray());
                    kmf.init(ks, configuration.getCertPassword().toCharArray());
                }
            } else {
                LOGGER.error(
                        "The keystore is not configured. "
                        + "Check the keystore-file, "
                        + "keystore-password and certpassword options.");
            }

            tmf.init(ks);

            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        } catch (KeyManagementException
                | NoSuchAlgorithmException
                | KeyStoreException
                | CertificateException
                | UnrecoverableKeyException ex) {
            logErrorAndExit(
                    "Couldn't start RESTHeart, error with specified keystore. "
                    + "Check the keystore-file, "
                    + "keystore-password and certpassword options. Exiting..",
                    ex, false, -1);
        } catch (FileNotFoundException ex) {
            logErrorAndExit(
                    "Couldn't start RESTHeart, keystore file not found. "
                    + "Check the keystore-file, "
                    + "keystore-password and certpassword options. Exiting..",
                    ex, false, -1);
        } catch (IOException ex) {
            logErrorAndExit(
                    "Couldn't start RESTHeart, error reading the keystore file. "
                    + "Check the keystore-file, "
                    + "keystore-password and certpassword options. Exiting..",
                    ex, false, -1);
        }

        Builder builder = Undertow.builder();

        if (configuration.isHttpsListener()) {
            builder.addHttpsListener(configuration.getHttpsPort(),
                    configuration.getHttpsHost(),
                    sslContext);

            if (configuration.getHttpsHost().equals("127.0.0.1")
                    || configuration.getHttpsHost().equalsIgnoreCase("localhost")) {
                LOGGER.warn("HTTPS listener bound to localhost:{}. "
                        + "Remote systems will be unable to connect to this server.",
                        configuration.getHttpsPort());
            } else {
                LOGGER.info("HTTPS listener bound at {}:{}",
                        configuration.getHttpsHost(), configuration.getHttpsPort());
            }
        }

        if (configuration.isHttpListener()) {
            builder.addHttpListener(configuration.getHttpPort(),
                    configuration.getHttpHost());

            if (configuration.getHttpHost().equals("127.0.0.1")
                    || configuration.getHttpHost().equalsIgnoreCase("localhost")) {
                LOGGER.warn("HTTP listener bound to localhost:{}. "
                        + "Remote systems will be unable to connect to this server.",
                        configuration.getHttpPort());
            } else {
                LOGGER.info("HTTP listener bound at {}:{}",
                        configuration.getHttpHost(), configuration.getHttpPort());
            }
        }

        if (configuration.isAjpListener()) {
            builder.addAjpListener(configuration.getAjpPort(),
                    configuration.getAjpHost());

            if (configuration.getAjpHost().equals("127.0.0.1")
                    || configuration.getAjpHost().equalsIgnoreCase("localhost")) {
                LOGGER.warn("AJP listener bound to localhost:{}. "
                        + "Remote systems will be unable to connect to this server.",
                        configuration.getAjpPort());
            } else {
                LOGGER.info("AJP listener bound at {}:{}",
                        configuration.getAjpHost(), configuration.getAjpPort());
            }
        }

        HANDLERS = getPipeline(authMechanisms,
                authorizers,
                tokenManager);

        // update buffer size in
        Exchange.updateBufferSize(configuration.getBufferSize());

        builder = builder
                .setIoThreads(configuration.getIoThreads())
                .setWorkerThreads(configuration.getWorkerThreads())
                .setDirectBuffers(configuration.isDirectBuffers())
                .setBufferSize(configuration.getBufferSize())
                .setHandler(HANDLERS);

        // starting from undertow 1.4.23 URL checks become much stricter
        // (undertow commit 09d40a13089dbff37f8c76d20a41bf0d0e600d9d)
        // allow unescaped chars in URL (otherwise not allowed by default)
        builder.setServerOption(UndertowOptions.ALLOW_UNESCAPED_CHARACTERS_IN_URL,
                configuration.isAllowUnescapedCharactersInUrl());

        LOGGER.debug("Allow unescaped characters in URL: {}",
                configuration.isAllowUnescapedCharactersInUrl());

        ConfigurationHelper.setConnectionOptions(builder, configuration);

        undertowServer = builder.build();
        undertowServer.start();
    }

    /**
     * logErrorAndExit
     *
     * @param message
     * @param t
     * @param silent
     * @param status
     */
    private static void logErrorAndExit(String message,
            Throwable t,
            boolean silent,
            int status) {
        logErrorAndExit(message, t, silent, true, status);
    }

    /**
     * logErrorAndExit
     *
     * @param message
     * @param t
     * @param silent
     * @param removePid
     * @param status
     */
    private static void logErrorAndExit(String message,
            Throwable t,
            boolean silent,
            boolean removePid,
            int status) {
        if (t == null) {
            LOGGER.error(message);
        } else {
            LOGGER.error(message, t);
        }
        stopServer(silent, removePid);
        System.exit(status);
    }

    /**
     * getHandlersPipe
     *
     * @param identityManager
     * @param authorizers
     * @param tokenManager
     * @return a GracefulShutdownHandler
     */
    private static GracefulShutdownHandler getPipeline(
            final Set<PluginRecord<AuthMechanism>> authMechanisms,
            final Set<PluginRecord<Authorizer>> authorizers,
            final PluginRecord<TokenManager> tokenManager
    ) {
        PluginsRegistryImpl
                .getInstance()
                .getRootPathHandler()
                .addPrefixPath("/", new RequestNotManagedHandler());

        LOGGER.debug("Content buffers maximun size "
                + "is {} bytes",
                MAX_CONTENT_SIZE);

        plugServices(authMechanisms, authorizers, tokenManager);

        plugProxies(configuration, authMechanisms, authorizers, tokenManager);

        plugStaticResourcesHandlers(configuration);

        return getBasePipeline();
    }

    /**
     * buildGracefulShutdownHandler
     *
     * @param paths
     * @return
     */
    private static GracefulShutdownHandler getBasePipeline() {
        return new GracefulShutdownHandler(
                new RequestLimitingHandler(
                        new RequestLimit(configuration.getRequestsLimit()),
                        new AllowedMethodsHandler(
                                new BlockingHandler(
                                        new ErrorHandler(
                                                new HttpContinueAcceptingHandler(
                                                        PluginsRegistryImpl
                                                                .getInstance()
                                                                .getRootPathHandler()))),
                                // allowed methods
                                HttpString.tryFromString(ExchangeKeys.METHOD.GET.name()),
                                HttpString.tryFromString(ExchangeKeys.METHOD.POST.name()),
                                HttpString.tryFromString(ExchangeKeys.METHOD.PUT.name()),
                                HttpString.tryFromString(ExchangeKeys.METHOD.DELETE.name()),
                                HttpString.tryFromString(ExchangeKeys.METHOD.PATCH.name()),
                                HttpString.tryFromString(ExchangeKeys.METHOD.OPTIONS.name()))));
    }

    /**
     * plug services
     *
     * @param paths
     * @param mechanisms
     * @param authorizers
     * @param tokenManager
     */
    @SuppressWarnings("unchecked")
    private static void plugServices(final Set<PluginRecord<AuthMechanism>> mechanisms,
            final Set<PluginRecord<Authorizer>> authorizers,
            final PluginRecord<TokenManager> tokenManager) {
        PluginsRegistryImpl.getInstance().getServices().stream().forEach(srv -> {
            var srvConfArgs = srv.getConfArgs();

            String uri;

            if (srvConfArgs == null
                    || !srvConfArgs.containsKey("uri")
                    || srvConfArgs.get("uri") == null) {
                uri = defaultURI(srv.getInstance());
            } else {
                if (!(srvConfArgs.get("uri") instanceof String)) {
                    LOGGER.error("Cannot start service {}:"
                            + " the configuration property 'uri' must be a string",
                            srv.getName());

                    return;
                } else {
                    uri = (String) srvConfArgs.get("uri");
                }
            }

            if (uri == null) {
                LOGGER.error("Cannot start service {}:"
                        + " the configuration property 'uri' is not defined"
                        + " and the service does not have a default value",
                        srv.getName());
                return;
            }

            if (!uri.startsWith("/")) {
                LOGGER.error("Cannot start service {}:"
                        + " the configuration property 'uri' must start with /",
                        srv.getName(),
                        uri);

                return;
            }

            boolean secured = srvConfArgs != null
                    && srvConfArgs.containsKey("secured")
                    && srvConfArgs.get("secured") instanceof Boolean
                    ? (boolean) srvConfArgs.get("secured")
                    : false;

            SecurityHandler securityHandler;

            if (secured) {
                securityHandler = new SecurityHandler(
                        mechanisms,
                        authorizers,
                        tokenManager);
            } else {
                var _fauthorizers = new LinkedHashSet<PluginRecord<Authorizer>>();

                PluginRecord<Authorizer> _fauthorizer = new PluginRecord(
                        "fullAuthorizer",
                        "authorize any operation to any user",
                        true,
                        FullAuthorizer.class
                                .getName(),
                        new FullAuthorizer(false),
                        null
                );

                _fauthorizers.add(_fauthorizer);

                securityHandler = new SecurityHandler(
                        mechanisms,
                        _fauthorizers,
                        tokenManager);
            }

            var _srv = pipe(new PipelineInfoInjector(),
                    new TracingInstrumentationHandler(),
                    new RequestLogger(),
                    new ServiceExchangeInitializer(),
                    new CORSHandler(),
                    new XPoweredByInjector(),
                    new RequestInterceptorsExecutor(REQUEST_BEFORE_AUTH),
                    new QueryStringRebuilder(),
                    securityHandler,
                    new RequestInterceptorsExecutor(REQUEST_AFTER_AUTH),
                    new QueryStringRebuilder(),
                    PipelinedWrappingHandler
                            .wrap(new ConfigurableEncodingHandler(
                                    PipelinedWrappingHandler
                                            .wrap(srv.getInstance()),
                                    configuration.isForceGzipEncoding())),
                    new ResponseInterceptorsExecutor(),
                    new ResponseSender()
            );

            PluginsRegistryImpl
                    .getInstance()
                    .plugPipeline(uri, _srv,
                            new PipelineInfo(SERVICE, uri, srv.getName()));

            LOGGER.info(ansi().fg(GREEN)
                    .a("URI {} bound to service {}, secured: {}")
                    .reset().toString(), uri, srv.getName(), secured);
        });
    }

    /**
     * plugProxies
     *
     * @param conf
     * @param paths
     * @param authMechanisms
     * @param identityManager
     * @param authorizers
     */
    private static void plugProxies(final Configuration conf,
            final Set<PluginRecord<AuthMechanism>> authMechanisms,
            final Set<PluginRecord<Authorizer>> authorizers,
            final PluginRecord<TokenManager> tokenManager) {
        if (conf.getProxies() == null || conf.getProxies().isEmpty()) {
            LOGGER.debug("No {} specified", ConfigurationKeys.PROXY_KEY);
            return;
        }

        conf.getProxies().stream().forEachOrdered((Map<String, Object> proxies) -> {
            String location = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_LOCATION_KEY, null, true);

            Object _proxyPass = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_PASS_KEY, null, true);

            if (location == null && _proxyPass != null) {
                LOGGER.warn("Location URI not specified for resource {} ",
                        _proxyPass);
                return;
            }

            if (location == null && _proxyPass == null) {
                LOGGER.warn("Invalid proxies entry detected");
                return;
            }

            // The number of connections to create per thread
            Integer connectionsPerThread = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_CONNECTIONS_PER_THREAD, 10,
                    true);

            Integer maxQueueSize = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_MAX_QUEUE_SIZE, 0, true);

            Integer softMaxConnectionsPerThread = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_SOFT_MAX_CONNECTIONS_PER_THREAD, 5, true);

            Integer ttl = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_TTL, -1, true);

            boolean rewriteHostHeader = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_REWRITE_HOST_HEADER, true, true);

            // Time in seconds between retries for problem server
            Integer problemServerRetry = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_PROBLEM_SERVER_RETRY, 10,
                    true);

            String name = Configuration.getOrDefault(proxies,
                    ConfigurationKeys.PROXY_NAME, null,
                    true);

            final Xnio xnio = Xnio.getInstance();

            final OptionMap optionMap = OptionMap.create(
                    Options.SSL_CLIENT_AUTH_MODE,
                    SslClientAuthMode.REQUIRED,
                    Options.SSL_STARTTLS,
                    true);

            XnioSsl sslProvider = null;

            try {
                sslProvider = xnio.getSslProvider(optionMap);
            } catch (GeneralSecurityException ex) {
                logErrorAndExit("error configuring ssl", ex, false, -13);
            }

            try {
                LoadBalancingProxyClient proxyClient
                        = new LoadBalancingProxyClient()
                                .setConnectionsPerThread(connectionsPerThread)
                                .setSoftMaxConnectionsPerThread(softMaxConnectionsPerThread)
                                .setMaxQueueSize(maxQueueSize)
                                .setProblemServerRetry(problemServerRetry)
                                .setTtl(ttl);

                if (_proxyPass instanceof String) {
                    proxyClient = proxyClient.addHost(
                            new URI((String) _proxyPass), sslProvider);
                } else if (_proxyPass instanceof List) {
                    for (Object proxyPassURL : ((Iterable<? extends Object>) _proxyPass)) {
                        if (proxyPassURL instanceof String) {
                            proxyClient = proxyClient.addHost(
                                    new URI((String) proxyPassURL), sslProvider);
                        } else {
                            LOGGER.warn("Invalid proxy pass URL {}, location {} not bound ",
                                    proxyPassURL, location);
                        }
                    }
                } else {
                    LOGGER.warn("Invalid proxy pass URL {}, location {} not bound ",
                            _proxyPass);
                }

                ProxyHandler proxyHandler = ProxyHandler.builder()
                        .setRewriteHostHeader(rewriteHostHeader)
                        .setProxyClient(proxyClient)
                        .build();

                var proxy = pipe(
                        new PipelineInfoInjector(),
                        new TracingInstrumentationHandler(),
                        new RequestLogger(),
                        new XPoweredByInjector(),
                        new RequestContentInjector(ON_REQUIRES_CONTENT_BEFORE_AUTH),
                        new RequestInterceptorsExecutor(REQUEST_BEFORE_AUTH),
                        new QueryStringRebuilder(),
                        new SecurityHandler(
                                authMechanisms,
                                authorizers,
                                tokenManager),
                        new AuthHeadersRemover(),
                        new XForwardedHeadersInjector(),
                        new RequestContentInjector(ON_REQUIRES_CONTENT_AFTER_AUTH),
                        new RequestInterceptorsExecutor(REQUEST_AFTER_AUTH),
                        new QueryStringRebuilder(),
                        new ConduitInjector(),
                        PipelinedWrappingHandler.wrap(
                                new ConfigurableEncodingHandler( // Must be after ConduitInjector
                                        proxyHandler,
                                        configuration.isForceGzipEncoding())));
                PluginsRegistryImpl
                        .getInstance()
                        .plugPipeline(location, proxy,
                                new PipelineInfo(PROXY, location, name));

                LOGGER.info(ansi().fg(GREEN)
                        .a("URI {} bound to proxy resource {}")
                        .reset().toString(), location, _proxyPass);
            } catch (URISyntaxException ex) {
                LOGGER.warn("Invalid location URI {}, resource {} not bound ",
                        location,
                        _proxyPass);
            }
        });
    }

    /**
     * plugStaticResourcesHandlers
     *
     * plug the static resources specified in the configuration file
     *
     * @param conf
     * @param pathHandler
     * @param authenticationMechanism
     * @param identityManager
     * @param accessManager
     */
    private static void plugStaticResourcesHandlers(
            final Configuration conf) {
        if (!conf.getStaticResourcesMounts().isEmpty()) {
            conf.getStaticResourcesMounts().stream().forEach(sr -> {
                try {
                    String path = (String) sr.get(STATIC_RESOURCES_MOUNT_WHAT_KEY);
                    String where = (String) sr.get(STATIC_RESOURCES_MOUNT_WHERE_KEY);
                    String welcomeFile = (String) sr.get(STATIC_RESOURCES_MOUNT_WELCOME_FILE_KEY);

                    Boolean embedded = (Boolean) sr.get(STATIC_RESOURCES_MOUNT_EMBEDDED_KEY);
                    if (embedded == null) {
                        embedded = false;
                    }

                    if (where == null || !where.startsWith("/")) {
                        LOGGER.error("Cannot bind static resources to {}. "
                                + "parameter 'where' must start with /", where);
                        return;
                    }

                    if (welcomeFile == null) {
                        welcomeFile = "index.html";
                    }

                    File file;

                    if (embedded) {
                        if (path.startsWith("/")) {
                            LOGGER.error("Cannot bind embedded static resources to {}. parameter 'where'"
                                    + "cannot start with /. the path is relative to the jar root dir"
                                    + " or classpath directory", where);
                            return;
                        }

                        try {
                            file = ResourcesExtractor.extract(Bootstrapper.class, 
                                    path);

                            if (ResourcesExtractor.isResourceInJar(Bootstrapper.class, 
                                    path)) {
                                TMP_EXTRACTED_FILES.put(path, file);
                                LOGGER.info("Embedded static resources {} extracted in {}", path, file.toString());
                            }
                        } catch (URISyntaxException | IOException | IllegalStateException ex) {
                            LOGGER.error("Error extracting embedded static resource {}", path, ex);
                            return;
                        }
                    } else if (!path.startsWith("/")) {
                        // this is to allow specifying the configuration file path relative
                        // to the jar (also working when running from classes)
                        URL location = Bootstrapper.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation();

                        File locationFile = new File(location.getPath());

                        Path _path = Paths.get(
                                locationFile.getParent()
                                        .concat(File.separator)
                                        .concat(path));

                        // normalize addresses https://issues.jboss.org/browse/UNDERTOW-742
                        file = _path.normalize().toFile();
                    } else {
                        file = new File(path);
                    }

                    if (file.exists()) {
                        ResourceHandler handler = resource(new FileResourceManager(file, 3))
                                .addWelcomeFiles(welcomeFile)
                                .setDirectoryListingEnabled(false);

                        PipelinedHandler ph = PipelinedHandler.pipe(
                                new PipelineInfoInjector(),
                                new RequestLogger(),
                                PipelinedWrappingHandler.wrap(handler)
                        );

                        PluginsRegistryImpl
                                .getInstance()
                                .plugPipeline(where, ph,
                                        new PipelineInfo(STATIC_RESOURCE,
                                                where,
                                                path));

                        LOGGER.info(ansi().fg(GREEN)
                                .a("URI {} bound to static resource {}")
                                .reset().toString(), where, file.getAbsolutePath());

                    } else {
                        LOGGER.error("Failed to bind URL {} to static resources {}."
                                + " Directory does not exist.", where, path);
                    }

                } catch (Throwable t) {
                    LOGGER.error("Cannot bind static resources to {}",
                            sr.get(STATIC_RESOURCES_MOUNT_WHERE_KEY), t);
                }
            });
        }
    }

    private Bootstrapper() {
    }

    @Parameters
    private static class Args {

        @Parameter(description = "<Configuration file>")
        private String configPath = null;

        @Parameter(names = "--fork", description = "Fork the process")
        private boolean isForked = false;

        @Parameter(names = {"--envFile", "--envfile", "-e"}, description = "Environment file name")
        private String envFile = null;

        @Parameter(names = {"--help", "-?"}, help = true, description = "This help message")
        private boolean help;
    }
}
