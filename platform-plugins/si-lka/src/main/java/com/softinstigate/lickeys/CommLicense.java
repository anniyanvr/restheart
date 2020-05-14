/*
 * Copyright SoftInstigate srl. All Rights Reserved.
 *
 *
 * The copyright to the computer program(s) herein is the property of
 * SoftInstigate srl, Italy. The program(s) may be used and/or copied only
 * with the written permission of SoftInstigate srl or in accordance with the
 * terms and conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied. This copyright notice must not be removed.
 */
package com.softinstigate.lickeys;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.restheart.utils.LogUtils;
import com.restheart.utils.ResourcesExtractor;
import static io.undertow.Handlers.resource;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.util.HttpString;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import org.restheart.Version;
import org.restheart.exchange.BsonRequest;
import org.restheart.exchange.BsonResponse;
import org.restheart.plugins.BsonInterceptor;
import static org.restheart.plugins.ConfigurablePlugin.argValue;
import org.restheart.plugins.ConfigurationScope;
import org.restheart.plugins.InitPoint;
import org.restheart.plugins.Initializer;
import org.restheart.plugins.InjectConfiguration;
import org.restheart.plugins.InjectPluginsRegistry;
import org.restheart.plugins.PluginsRegistry;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.utils.ChannelReader;
import org.restheart.utils.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
@RegisterPlugin(name = "commLicense",
        description = "Activates the commercial license",
        priority = Integer.MIN_VALUE,
        initPoint = InitPoint.AFTER_STARTUP)
public class CommLicense implements Initializer {
    private static final Logger LOGGER
            = LoggerFactory.getLogger("com.restheart.CommLicense");

    public static final String BASE_PATH_PROP_NAME = "lk-dir";

    public static final String ACCEPT_LICENSE_AGREEMENT_PROP_NAME
            = "ACCEPT_LICENSE_AGREEMENT";

    public static final String DEFAULT_BASE_PATH = "lickey";
    private static final String LIC_FILE_NAME = "COMM-LICENSE.txt";
    private static final String LIC_KEY_FILE_NAME = "comm-license.key";
    private static final String LIC_APPROVAL_FILE_NAME = "license-approval.txt";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("MM/dd/yyyy");

    public enum STATUS {
        INITIALIZIG, LICENSE_NOT_YET_ACCEPTED, OK
    };

    private STATUS status = STATUS.INITIALIZIG;

    private static PluginsRegistry REGISTRY;

    private Map<String, Object> conf;

    @InjectPluginsRegistry
    public void setPluginRegistry(PluginsRegistry pluginRegistry) {
        REGISTRY = pluginRegistry;
    }

    @InjectConfiguration(scope = ConfigurationScope.ALL)
    public void setConfiguration(Map<String, Object> conf) {
        this.conf = conf;
    }

    @Override
    public void init() {
        var licensePath = checkLicenseKeyDirectory();

        // if lk does not exist, start the form
        var licenseKeyPath = checkLicenseKey();

        if (licenseKeyPath == null) {
            this.status = STATUS.LICENSE_NOT_YET_ACCEPTED;
            requestAcceptingLicense();

            LOGGER.warn("All requests are temporarily blocked.");

            startAcceptanceForm(null, licensePath);

            licenseKeyPath = checkLicenseKey();

            if (licenseKeyPath == null) {
                LogUtils.boxedError(LOGGER,
                        "Cannot find the License Key after having accepted it.",
                        "",
                        "Try againg, restartign the service");

                System.exit(-5050);
            }
        }

        LicenseKeyClaims licenseKeyClaims;

        try {
            licenseKeyClaims = verifyLicenseKey(licenseKeyPath, licensePath);
        } catch (Throwable t) {
            var backupFileName = licenseKeyPath.getFileName()
                    + "-"
                    + System.currentTimeMillis()
                    + ".backup";

            LOGGER.error("Invalid License Key: {}", t.getMessage());

            if (Files.exists(licenseKeyPath)) {
                try {
                    Files.move(licenseKeyPath, licenseKeyPath.resolveSibling(
                            backupFileName));

                    LOGGER.info("Invalid license backed up as {}", backupFileName);

                    LogUtils.boxedError(LOGGER,
                            "Invalid License Key.",
                            "",
                            "The invalid key has been removed (and backed up)",
                            "",
                            "Restart RESTHeart to add a new license key",
                            "",
                            "Get a free trial license at https://restheart.org/get");

                } catch (IOException iex) {
                    LOGGER.error("Could not remove the invalid license {}",
                            licenseKeyPath, iex);

                    LogUtils.boxedError(LOGGER,
                            "Invalid License Key.",
                            "",
                            "You need to update your license key",
                            "",
                            "Get a free trial license at https://restheart.org");
                }
            }

            licenseKeyClaims = null;
            System.exit(-5004);
        }

        // check if license has been accepted, i.e. approved
        if (checkLicenseAcceptance()) {
            this.status = STATUS.OK;
            LogUtils.boxedInfo(LOGGER,
                    "This instance of RESTHeart is licenced under the Terms and",
                    "Conditions of the Commercial License Agreement.");

            LOGGER.info("The License Agreement is available at {}", licensePath);
        } else {
            if (isLicenseAcceptedViaProperty()) {
                try {
                    registerAcceptance(licenseKeyClaims.getJti());
                    this.status = STATUS.OK;
                } catch (AccessDeniedException ex) {
                    LOGGER.warn("Coudn't register the license approval "
                            + "because the directory {} is not writable. "
                            + "Restarting the server requires it to be approved again.",
                            licensePath.getParent());
                    this.status = STATUS.OK;
                } catch (IOException | URISyntaxException ex) {
                    LOGGER.error("Error registering license approval: {}",
                            ex.getMessage());
                    System.exit(-5021);
                }
            } else {
                this.status = STATUS.LICENSE_NOT_YET_ACCEPTED;
                // block all requests to restheart
                requestAcceptingLicense();

                LOGGER.warn("All requests are temporarily blocked.");

                startAcceptanceForm(licenseKeyClaims.getJti(), licensePath);
            }
        }
    }

    /**
     *
     * @return the path of the license text
     */
    private Path checkLicenseKeyDirectory() {
        Path licenseDirPath = null;
        Path licensePath = null;

        boolean error = false;

        try {
            licenseDirPath = getAbsolutePath("");
            licensePath = getAbsolutePath(LIC_FILE_NAME);

            if (Files.notExists(licenseDirPath)
                    || !Files.isDirectory(licenseDirPath)
                    || Files.notExists(licensePath)
                    || !Files.isReadable(licensePath)) {
                error = true;
            }
        } catch (URISyntaxException ex) {
            error = true;
        }

        if (error) {
            LOGGER.error(" License Key directory {} not found", licenseDirPath);
            LogUtils.boxedError(LOGGER,
                    "Cannot find the License Key directory.",
                    "",
                    "Specify the license key directory with:",
                    "",
                    "$ java -Dlk-dir=<dir> -jar restheart-platform-core.jar");

            System.exit(-5002);
        }

        return licensePath;
    }

    /**
     *
     * @return the path of the license key, null if it does not exists
     */
    private Path checkLicenseKey() {
        try {
            var licenseKey = getAbsolutePath(LIC_KEY_FILE_NAME);

            if (Files.notExists(licenseKey)) {
                return null;
            } else {
                return licenseKey;
            }
        } catch (URISyntaxException ex) {
            LOGGER.error("Wrong License key path: {}", ex.getMessage());
            System.exit(-5003);

            throw new RuntimeException(ex);
        }
    }

    /**
     *
     * @return the verified license key claims
     */
    private LicenseKeyClaims verifyLicenseKey(
            Path licenseKeyPath,
            Path licensePath)
            throws InvalidLicenseKeyException {
        String licenseKey = null;

        try {
            licenseKey = new String(getFile(licenseKeyPath),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LogUtils.boxedError(LOGGER,
                    "Cannot read the License Key");

            LOGGER.info("License Key must be readable at {}", licenseKeyPath);

            System.exit(-5002);
        }

        // verify license key signature
        // in case of error, throws InvalidLicenseKeyException
        DecodedJWT verifiedLicense = verifyLicenseKey(licenseKey);

        LicenseKeyClaims licenseKeyClaims = new LicenseKeyClaims(
                verifiedLicense.getClaims());

        LOGGER.info("License Key\n".concat(licenseKeyClaims.toString()));

        // check integrity of the license text file comparing its hash to
        // license key claim license-doc-hash
        String licenseExprectedHash = licenseKeyClaims.getLicenseHash();

        if (licenseExprectedHash != null) {
            try {
                if (!checkLicenseTxtHash(licenseExprectedHash, licensePath)) {
                    LogUtils.boxedError(LOGGER,
                            "The License Agreement COMM-LICENSE.txt was tampered",
                            "",
                            "Restore it and restart RESTHeart");

                    System.exit(-5011);
                }
            } catch (IOException ex) {
                LOGGER.error("Cannot read the License Agreement. "
                        + "It must be readable at {}: {}",
                        licensePath,
                        ex.getMessage());
                System.exit(-5010);
            } catch (NoSuchAlgorithmException ex) {
                LOGGER.error("Error generating hash of the license: {}",
                        ex.getMessage());
                System.exit(-5012);
            }
        }

        if (true && checkSubscriptionPeriod(licenseKeyClaims.getSubscriptionEnd())) {
            LOGGER.info("Subscription period ends on {}",
                    LocalDate.ofInstant(licenseKeyClaims.getSubscriptionEnd(),
                            ZoneOffset.UTC));
        } else {
            LOGGER.error("The License Key is not valid for "
                    + "this version of RESTHeart (build time {}) "
                    + "because the subscription period expires on {}",
                    LocalDate.ofInstant(Version.getInstance().getBuildTime(),
                            ZoneOffset.UTC).format(DATE_FORMAT),
                    LocalDate.ofInstant(licenseKeyClaims.getSubscriptionEnd(),
                            ZoneOffset.UTC).format(DATE_FORMAT));

            throw new InvalidLicenseKeyException("The License Key is not valid for this version of RESTHeart.");
        }

        return licenseKeyClaims;
    }

    /**
     * convert base64 to base64url to base64 replacing + with - and / with _
     *
     * @param str
     * @return
     */
    private static String base64Tobase64url(String str) {
        if (str == null) {
            return null;
        }

        return str
                .replace('+', '-')
                .replace('/', '_');
    }

    private DecodedJWT verifyLicenseKey(String licenseKey)
            throws InvalidLicenseKeyException {
        Algorithm algorithm = null;

        try {
            algorithm = Algorithm.RSA512(SiPublicKey.getRSAPublicKey(), null);
        } catch (Exception ex) {
            LOGGER.error("Error with private key: " + ex.getMessage());
            System.exit(-5001);
        }

        JWTVerifier verifier = JWT.require(algorithm)
                .withIssuer("SoftInstigate Srl, Italy")
                .build();

        try {
            return verifier.verify(base64Tobase64url(licenseKey));
        } catch (JWTVerificationException ex) {
            throw new InvalidLicenseKeyException(ex.getMessage(), ex);
        }
    }

    /**
     * this duplicates restheart-platform-security
     * RHCoreConnector.requestAcceptingLicense()
     */
    /**
     * This resembles the method RHCoreConnector.requestAcceptingLicense()
     * However, in this case we have server possibly accepting connections with
     * different listeners
     */
    private void requestAcceptingLicense() {
        String host;
        int port;
        String prot;

        boolean httpsListener;
        String httpsHost;
        Integer httpsPort;

        try {
            httpsListener = argValue(conf, "https-listener");
            httpsHost = argValue(conf, "https-host");
            httpsPort = argValue(conf, "https-port");
        } catch (Throwable t) {
            httpsListener = false;
            httpsHost = "0.0.0.0";
            httpsPort = 8080;
        }

        boolean httpListener;
        String httpHost;
        Integer httpPort;

        try {
            httpListener = argValue(conf, "http-listener");
            httpHost = argValue(conf, "https-host");
            httpPort = argValue(conf, "http-port");
        } catch (Throwable t) {
            httpListener = false;
            httpHost = "0.0.0.0";
            httpPort = 8080;
        }

        if (httpListener) {
            host = httpHost;
            port = httpPort;
            prot = "http";
        } else if (httpsListener) {
            host = httpsHost;
            port = httpsPort;
            prot = "https";
        } else {
            // ajp listener
            LogUtils.boxedWarn(LOGGER,
                    "The License Agreement has not yet been accepted.",
                    "",
                    "Only the AJP listener is enabled: edit the configuration",
                    "to enable the HTTP(S) listener (or use a proxy).",
                    "",
                    "More information at",
                    "https://restheart.org/docs/setup#accept-license");

            return;
        }

        if ("127.0.0.1".equals(host)
                || "localhost".equals(host)) {
            LogUtils.boxedWarn(LOGGER,
                    "The License Agreement has not yet been accepted.",
                    "",
                    "Please open your browser at "
                    + prot + "://localhost:"
                    + port + "/license and",
                    "accept the license to continue.",
                    "",
                    "The HTTP listener is bound to "
                    + "localhost: accept the license from",
                    "a browser running on the same "
                    + "host or edit the configuration.",
                    "",
                    "More information at",
                    "https://restheart.org/docs/setup#accept-license");
        } else if ("0.0.0.0".equals(host)) {
            try {
                host = Utils.getLocalHostLANAddress().getHostAddress();
            } catch (UnknownHostException ex) {
                // nothing to do
            }

            LogUtils.boxedWarn(LOGGER,
                    "The License Agreement has not yet been accepted.",
                    "",
                    "Please open your browser at " + prot + "://"
                    + host + ":" + port + "/license and",
                    "accept the license to continue.",
                    "",
                    "We have detected your LAN address; to accept the license from",
                    "an external network use the host's public IP.",
                    "",
                    "More information at",
                    "https://restheart.org/docs/setup#accept-license");
        } else {
            LogUtils.boxedWarn(LOGGER,
                    "The License Agreement has not yet been accepted.",
                    "",
                    "Please open your browser at "
                    + prot + "://" + host + ":" + port + "/license and",
                    "accept the license to continue.",
                    "",
                    "More information at",
                    "https://restheart.org/docs/setup#accept-license");
        }
    }

    public static STATUS getStatus() {
        var _clpr = REGISTRY
                .getInitializers()
                .stream()
                .filter(pr -> "commLicense".equals(pr.getName()))
                .findFirst();

        try {
            var clpr = _clpr.orElseThrow();

            if (clpr.getClassName().equals(CommLicense.class.getName())) {
                return ((CommLicense) clpr.getInstance()).status;
            } else {
                LOGGER.error("Wrong commLicense initializer.");
                System.exit(-6001);
            }
        } catch (NoSuchElementException nsee) {
            LOGGER.error("Missing required commLicense initializer.");
            System.exit(-6000);
        } catch (Throwable t) {
            LOGGER.error("Error getting license status.", t);
            System.exit(-6002);
        }

        return null;
    }

    /**
     *
     * @return false if the RH build time is later than subscription period end
     * date
     */
    private static boolean checkSubscriptionPeriod(Instant subscriptionEnd) {
        return Version.getInstance().getBuildTime().isBefore(subscriptionEnd);
    }

    /**
     * checks if license is accepted via ACCEPT_LICENSE_AGREEMENT java property
     */
    private static boolean isLicenseAcceptedViaProperty() {
        return "true".equalsIgnoreCase(
                System.getProperty(ACCEPT_LICENSE_AGREEMENT_PROP_NAME));
    }

    private static final String ROOT_PREFIX_PATH = "/license";
    private static final String ACCEPT_EXACT_PATH = "/license/accept";
    private static final String LICENSE_TEXT_EXACT_PATH = "/license/text";
    private static final String LICENSE_SAVE_EXACT_PATH = "/license/save";

    /**
     * Start an http server with the form to approve the license
     *
     * @param licenseKeyId if null, also allow saving license
     * @param licensePath
     */
    private void startAcceptanceForm(String licenseKeyId, Path licensePath) {
        final PathHandler rhRootPathHandler = REGISTRY.getRootPathHandler();
        final CountDownLatch done = new CountDownLatch(1);

        String licenseAcceptFormPath = "license-accept-form";
        File licenseAcceptFormFile = null;

        try {
            licenseAcceptFormFile = ResourcesExtractor
                    .extract(this.getClass().getName(), licenseAcceptFormPath);
        } catch (URISyntaxException | IOException ex) {
            LOGGER.error("Error extracting License form files: {}",
                    ex.getMessage());
            System.exit(-5060);
        } catch (IllegalStateException ex) {
            LOGGER.error("Error extracting License form files: {}",
                    ex.getMessage());
            System.exit(-5061);
        }

        rhRootPathHandler.addPrefixPath(ROOT_PREFIX_PATH,
                resource(new FileResourceManager(licenseAcceptFormFile, 3))
                        .setDirectoryListingEnabled(false));

        if (licenseKeyId == null) {
            rhRootPathHandler.addExactPath(LICENSE_SAVE_EXACT_PATH,
                    licenseSaver());
        }

        rhRootPathHandler.addExactPath(ACCEPT_EXACT_PATH,
                licenseActivator(done, licenseKeyId, licensePath));

        rhRootPathHandler.addExactPath(LICENSE_TEXT_EXACT_PATH,
                webContentSender(licensePath));

        // wait until license is accepted
        try {
            done.await();
        } catch (InterruptedException ex) {
            LOGGER.error("Error waiting for License Agreement to be accepted.");
            try {
                ResourcesExtractor.deleteTempDir(this.getClass().getName(),
                        licenseAcceptFormPath,
                        licenseAcceptFormFile);
            } catch (URISyntaxException | IOException ex2) {
                // nothing to do
            }

            System.exit(-5050);
        } finally {
            try {
                ResourcesExtractor.deleteTempDir(this.getClass().getName(),
                        licenseAcceptFormPath,
                        licenseAcceptFormFile);
            } catch (URISyntaxException | IOException ex) {
                // nothing to do
            }
        }

        this.status = STATUS.OK;

        try {
            rhRootPathHandler.removeExactPath(ACCEPT_EXACT_PATH);
            rhRootPathHandler.removeExactPath(LICENSE_TEXT_EXACT_PATH);

            if (licenseKeyId == null) {
                rhRootPathHandler.removeExactPath(LICENSE_SAVE_EXACT_PATH);
            }

            rhRootPathHandler.removePrefixPath(ROOT_PREFIX_PATH);
        } catch (Throwable t) {
            LOGGER.warn("Cannot stop License Agreement acceptance form", t);
        }
    }

    @RegisterPlugin(name = "", description = "")
    class Blocker implements BsonInterceptor {
        @Override
        public void handle(BsonRequest request, BsonResponse response) throws Exception {
            response.setInError(HttpStatus.SC_NOT_ACCEPTABLE,
                    "Request not executed. "
                    + "License Agreement not yet accepted.");
        }

        @Override
        public boolean resolve(BsonRequest request, BsonResponse response) {
            return true;
        }
    }

    private HttpHandler licenseActivator(CountDownLatch done,
            String licenseKeyId,
            Path licensePath) {
        return (HttpServerExchange hse) -> {
            if (HttpString.tryFromString("POST")
                    .equals(hse.getRequestMethod())) {
                LOGGER.info("License Agreement accepted.");

                try {
                    registerAcceptance(licenseKeyId);
                } catch (AccessDeniedException ex) {
                    LOGGER.warn("Coudn't register the license approval "
                            + "because the directory {} is not writable. "
                            + "Restarting the server requires it to be approved again.",
                            licensePath.getParent());
                } catch (IOException | URISyntaxException ex) {
                    LOGGER.error("Error registering license approval: {}",
                            ex.getMessage());
                    hse.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
                }

                hse.setStatusCode(HttpStatus.SC_OK);
                // release the latch
                done.countDown();
            } else if (HttpString.tryFromString(
                    "OPTIONS").equals(hse.getRequestMethod())) {
                hse.getResponseHeaders()
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Origin"), "*")
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Methods"), "POST")
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Headers"),
                                "Accept, Accept-Encoding, "
                                + "Content-Length, Content-Type, "
                                + "Host, Origin, User-Agent");
                hse.setStatusCode(HttpStatus.SC_OK);
            } else {
                LOGGER.debug("License Agreement not accepted");
                hse.setStatusCode(HttpStatus.SC_NOT_MODIFIED);
            }

            hse.endExchange();
        };
    }

    private HttpHandler licenseSaver() {
        return (HttpServerExchange hse) -> {
            if (HttpString.tryFromString("GET")
                    .equals(hse.getRequestMethod())) {
                hse.setStatusCode(HttpStatus.SC_OK);
            } else if (HttpString.tryFromString("POST")
                    .equals(hse.getRequestMethod())) {
                try {
                    var lk = ChannelReader.read(hse.getRequestChannel())
                            .trim();

                    if (verifyLicenseKey(lk) != null) {
                        Files.writeString(
                                getAbsolutePath(LIC_KEY_FILE_NAME),
                                lk);

                        hse.setStatusCode(HttpStatus.SC_OK);
                    } else {
                        hse.setStatusCode(HttpStatus.SC_BAD_REQUEST);
                    }

                } catch (IOException ioe) {
                    LOGGER.error("Error saving the license approval.", ioe);
                    hse.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
                }
            } else if (HttpString.tryFromString("OPTIONS").equals(hse.getRequestMethod())) {
                hse.getResponseHeaders()
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Origin"), "*")
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Methods"), "POST")
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Headers"),
                                "Accept, Accept-Encoding, "
                                + "Content-Length, Content-Type, "
                                + "Host, Origin, User-Agent");
                hse.setStatusCode(HttpStatus.SC_OK);
            } else {
                hse.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
            }

            hse.endExchange();
        };
    }

    private static void registerAcceptance(String licenseKeyId)
            throws IOException, URISyntaxException {
        try {
            String fingerprint = "{\n"
                    + "  'timestamp': " + "'" + Instant.now().toString() + "',\n"
                    + "  'licenseKeyId': " + (licenseKeyId == null
                            ? "null"
                            : "'" + licenseKeyId + "'") + ",\n"
                    + "  'installationId': " + "'" + installationId() + "'\n"
                    + "}";

            Files.write(getAbsolutePath(LIC_APPROVAL_FILE_NAME),
                    fingerprint.getBytes());
        } catch (IOException ex) {
            try {
                Files.delete(getAbsolutePath(LIC_APPROVAL_FILE_NAME));
            } catch (IOException ioe2) {
                // nothing to do
            }

            throw ex;
        }
    }

    private static HttpHandler webContentSender(Path licensePath) {
        return (HttpServerExchange hse) -> {
            if (HttpString.tryFromString("GET")
                    .equals(hse.getRequestMethod())) {
                hse.getResponseHeaders().add(HttpString
                        .tryFromString("Content-Type"),
                        "text/plain; charset=utf-8");
                hse.setStatusCode(HttpStatus.SC_OK);
                hse.getResponseSender().send(new String(
                        getFile(licensePath),
                        StandardCharsets.UTF_8));
            } else if (HttpString.tryFromString("OPTIONS")
                    .equals(hse.getRequestMethod())) {
                hse.getResponseHeaders()
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Origin"), "*")
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Methods"), "GET")
                        .put(HttpString.tryFromString(
                                "Access-Control-Allow-Headers"), "Accept, "
                                + "Accept-Encoding, Content-Length, "
                                + "Content-Type, Host, "
                                + "Origin, User-Agent");
                hse.setStatusCode(HttpStatus.SC_OK);
            } else {
                hse.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
            }

            hse.endExchange();
        };
    }

    /**
     *
     * @return an installation id
     */
    private static String installationId() {
        return new BigInteger(256, new SecureRandom())
                .toString(Character.MAX_RADIX);
    }

    private static boolean checkLicenseTxtHash(String expectedHash,
            Path licPath) throws IOException, NoSuchAlgorithmException {
        byte[] licTxt = getFile(licPath);

        Hasher hasher = Hashing.sha256().newHasher();

        hasher.putBytes(licTxt);

        String licHash = hasher.hash().toString();

        LOGGER.trace("Hash of License Agreement {} is {}", licPath, licHash);

        return expectedHash.equals(licHash);
    }

    /**
     *
     * @param relativePath relative to the jar containing this class (e.g.
     * si-lka.jar)
     * @return the content of the file
     * @throws IOException
     */
    private static byte[] getFile(Path filePath) throws IOException {
        return Files.readAllBytes(filePath);

    }

    /**
     *
     * @return the URI of thejar containing this class (e.g. si-lka.jar)
     * @throws URISyntaxException
     */
    private static URI getJarURI() throws URISyntaxException {
        return CommLicense.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI();
    }

    /**
     *
     * @param relativePath the path relative to the base path set by the system
     * property BASE_PATH_PROP_NAME or to the jar containing this class (e.g.
     * si-lka.jar)
     *
     * @throws URISyntaxException
     */
    private static Path getAbsolutePath(String relativePath)
            throws URISyntaxException {
        final String BASE_PATH = System.getProperty(BASE_PATH_PROP_NAME);

        if (BASE_PATH == null) {
            var path = DEFAULT_BASE_PATH
                    .concat("/")
                    .concat(relativePath);

            return Paths.get(getJarURI().resolve(path))
                    .toAbsolutePath();
        } else {
            return Paths.get(BASE_PATH, relativePath)
                    .toAbsolutePath();
        }
    }

    /**
     *
     * @return true if the license has been already accepted, checking if the
     * license-approval.txt file
     */
    private boolean checkLicenseAcceptance() {
        try {
            return Files.exists(getAbsolutePath(LIC_APPROVAL_FILE_NAME));
        } catch (URISyntaxException ex) {
            return false;

        }
    }
}

class SiPublicKey {
    public static final transient String PKEY
            = "-----BEGIN PUBLIC KEY-----\n"
            + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuosNA4axVv4sUhsTSqUl\n"
            + "T9Taq6chw/uIMpNhWygJqh3IC9fZZDPyheO+3r+JzhK1UD1HUixyMddXvAo5GUjg\n"
            + "wRcMHB97ysM9IJGUkdzoZXJVSkFPZf5NRBtHufWc5jpYZZxSklgqIdXxno775vAk\n"
            + "gLxiAM5IucS8gJB+d9EwAFBbWTDQdV0qX+w25+ytAk458Smylpi5IniWIHJ191t+\n"
            + "vzQqyKK1+wWXMiMCZkAlm4PQs9lfoIorkSnBr3aGyn05v8g7yLpmkLzxaPZoxA6g\n"
            + "G4Leb6/lyr+8RxSiiUO3Bm2WGCKpRfFu4L4kCZDXCl1E9LwgRNwE6DIctyXyNwoc\n"
            + "4QIDAQAB\n"
            + "-----END PUBLIC KEY-----";

    /**
     * @see https://adangel.org/2016/08/29/openssl-rsa-java/
     *
     * @param publicKeyPEM
     * @return the SoftInstigate license public key
     * @throws Exception
     */
    public static RSAPublicKey getRSAPublicKey() throws Exception {
        // strip of header, footer, newlines, whitespaces
        String publicKeyPEM = PKEY
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        // decode to get the binary DER representation
        byte[] publicKeyDER = Base64.getDecoder().decode(publicKeyPEM);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey publicKey = keyFactory.generatePublic(
                new X509EncodedKeySpec(publicKeyDER));
        return (RSAPublicKey) publicKey;
    }
}
