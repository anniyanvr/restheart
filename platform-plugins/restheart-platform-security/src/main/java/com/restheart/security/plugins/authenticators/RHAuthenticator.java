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
package com.restheart.security.plugins.authenticators;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.restheart.security.net.Client;
import com.restheart.security.plugins.interceptors.UserPwdHasher;
import com.restheart.security.plugins.interceptors.UserPwdRemover;
import com.restheart.security.predicates.DenyFilterOnUserPwdPredicate;
import io.undertow.security.idm.Account;
import io.undertow.security.idm.Credential;
import io.undertow.security.idm.DigestCredential;
import io.undertow.security.idm.PasswordCredential;
import io.undertow.util.HexConverter;
import static io.undertow.util.RedirectBuilder.UTF_8;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import org.mindrot.jbcrypt.BCrypt;
import org.restheart.security.Bootstrapper;
import org.restheart.security.ConfigurationException;
import org.restheart.security.cache.Cache;
import org.restheart.security.cache.CacheFactory;
import org.restheart.security.cache.LoadingCache;
import org.restheart.security.handlers.GlobalSecurityPredicatesAuthorizer;
import com.restheart.security.net.Request;
import java.io.IOException;
import static org.restheart.security.handlers.injectors.XForwardedHeadersInjector.getXForwardedAccountIdHeaderName;
import static org.restheart.security.handlers.injectors.XForwardedHeadersInjector.getXForwardedRolesHeaderName;
import org.restheart.security.plugins.Authenticator;
import static org.restheart.security.plugins.ConfigurablePlugin.argValue;
import org.restheart.security.plugins.PluginsRegistry;
import org.restheart.security.plugins.authenticators.PwdCredentialAccount;
import org.restheart.security.utils.HttpStatus;
import org.restheart.security.utils.URLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class RHAuthenticator implements Authenticator {

    private static final Logger LOGGER
            = LoggerFactory.getLogger(RHAuthenticator.class);

    public static final String X_FORWARDED_ACCOUNT_ID = "rhAuthenticator";
    public static final String X_FORWARDED_ROLE = "RESTHeart";

    private final String name;
    private URI restheartBaseUrl = null;
    private URI dbUrl = null;
    private URI collUrl = null;
    private String usersUri = null;
    private String propId = "_id";
    private String propPassword = "password";
    private String jsonPathRoles = "$.roles";
    private Boolean bcryptHashedPassword = false;
    private Integer bcryptComplexity = 12;
    private Boolean createUser = false;
    private String createUserDocument = null;
    private Boolean cacheEnabled = false;
    private Integer cacheSize = 1_000; // 1000 entries
    private Integer cacheTTL = 60 * 1_000; // 1 minute
    private Cache.EXPIRE_POLICY cacheExpirePolicy
            = Cache.EXPIRE_POLICY.AFTER_WRITE;

    private LoadingCache<String, PwdCredentialAccount> USERS_CACHE = null;

    private static final transient Cache<String, String> USERS_PWDS_CACHE
            = CacheFactory.createLocalCache(
                    1_000l,
                    Cache.EXPIRE_POLICY.AFTER_READ,
                    20 * 60 * 1_000l);

    /**
     *
     * @param name
     * @param args
     * @throws java.io.FileNotFoundException
     * @throws org.restheart.security.ConfigurationException
     */
    public RHAuthenticator(final String name, final Map<String, Object> args)
            throws FileNotFoundException, ConfigurationException {
        this.name = name;
        this.restheartBaseUrl = Bootstrapper.getConfiguration()
                .getRestheartBaseUrl();

        this.usersUri = URLUtils.removeTrailingSlashes(
                argValue(args, "users-collection-uri"));

        this.dbUrl = this.restheartBaseUrl
                .resolve(restheartBaseUrl.getPath()
                        .concat(usersUri.substring(0, usersUri.lastIndexOf("/"))));

        this.collUrl = this.restheartBaseUrl.resolve(this.restheartBaseUrl.getPath()
                .concat(usersUri));

        this.cacheEnabled = argValue(args, "cache-enabled");
        this.cacheSize = argValue(args, "cache-size");
        this.cacheTTL = argValue(args, "cache-ttl");

        String _cacheExpirePolicy = argValue(args, "cache-expire-policy");
        if (_cacheExpirePolicy != null) {
            try {
                this.cacheExpirePolicy = Cache.EXPIRE_POLICY
                        .valueOf((String) _cacheExpirePolicy);
            } catch (IllegalArgumentException iae) {
                throw new ConfigurationException(
                        "wrong configuration file format. "
                        + "cache-expire-policy valid values are "
                        + Arrays.toString(Cache.EXPIRE_POLICY.values()));
            }
        }

        this.bcryptHashedPassword = argValue(args, "bcrypt-hashed-password");

        this.bcryptComplexity = argValue(args, "bcrypt-complexity");

        this.createUser = argValue(args, "create-user");
        this.createUserDocument = argValue(args, "create-user-document");

        // check createUserDocument
        try {
            JsonParser.parseString(this.createUserDocument);
        } catch (JsonParseException ex) {
            throw new ConfigurationException(
                    "wrong configuration file format. "
                    + "create-user-document must be a json document", ex);
        }

        this.propId = argValue(args, "prop-id");

        if (this.propId.startsWith("$")) {
            throw new ConfigurationException("prop-id must be "
                    + "a root property name not a json path expression. "
                    + "It can use the dot notation.");
        }

        this.propPassword = argValue(args, "prop-password");

        if (this.propPassword.contains(".")) {
            throw new ConfigurationException("prop-password must be "
                    + "a root level property and cannot contain the char '.'");
        }

        this.jsonPathRoles = argValue(args, "json-path-roles");

        if (this.cacheEnabled) {
            this.USERS_CACHE = CacheFactory.createLocalLoadingCache(
                    this.cacheSize,
                    this.cacheExpirePolicy,
                    this.cacheTTL, (String key) -> {
                        return this.findAccount(key);
                    });
        }

        if (!checkUserCollection()) {
            LOGGER.error("Users collection does not exist and could not be created");
        } else if (this.createUser) {
            LOGGER.trace("Create user option enabled");

            if (countAccounts() < 1) {
                createDefaultAccount();
                LOGGER.info("No user found. Created default user with _id {}",
                        JsonParser.parseString(this.createUserDocument)
                                .getAsJsonObject().get(this.propId));
            } else {
                LOGGER.trace("Not creating default user since users exist");
            }
        }

        // add a global security predicate to deny requests
        // to users collection, containing a filter on password property
        // this avoids clients to potentially steal passwords
        GlobalSecurityPredicatesAuthorizer
                .getGlobalSecurityPredicates()
                .add(new DenyFilterOnUserPwdPredicate(
                        usersUri,
                        propPassword));

        // add a response interceptor to filter out the password property
        // from the response
        PluginsRegistry.getInstance()
                .getResponseInterceptors()
                .add(new UserPwdRemover(
                        usersUri,
                        propPassword));

        // add a request interceptor to hash the password property
        // in the write requests
        if (this.bcryptHashedPassword) {
            PluginsRegistry.getInstance()
                    .getRequestInterceptors()
                    .add(new UserPwdHasher(
                            usersUri,
                            propPassword,
                            bcryptComplexity));
        }
    }

    @Override
    public Account verify(Account account) {
        return account;
    }

    @Override
    public Account verify(String id, Credential credential) {
        if (credential == null) {
            LOGGER.debug("cannot verify null credential");
            return null;
        }

        PwdCredentialAccount ref = getAccount(id);

        boolean verified;

        if (credential instanceof PasswordCredential) {
            verified = verifyPasswordCredential(
                    ref,
                    (PasswordCredential) credential);
        } else if (credential instanceof DigestCredential) {
            verified = verifyDigestCredential(
                    ref,
                    (DigestCredential) credential);
        } else {
            LOGGER.warn("{} does not support credential of type {}",
                    name,
                    credential.getClass().getSimpleName());
            verified = false;
        }

        if (verified) {
            updateAuthTokenCache(ref);
            return ref;
        } else {
            return null;
        }
    }

    /**
     *
     * @param expectedPassword
     * @param credential
     * @return true if credential verifies successfully against ref account
     */
    private boolean verifyPasswordCredential(
            PwdCredentialAccount ref,
            PasswordCredential credential) {
        if (ref == null
                || ref.getPrincipal() == null
                || ref.getPrincipal().getName() == null
                || ref.getCredentials() == null
                || ref.getCredentials().getPassword() == null
                || credential == null || credential.getPassword() == null) {
            return false;
        }

        return checkPassword(
                ref.getPrincipal().getName(),
                this.bcryptHashedPassword,
                credential.getPassword(),
                ref.getCredentials().getPassword());
    }

    /**
     *
     * @param principalName
     * @param expectedPassword
     * @param credential
     * @return true if password verified successfully
     */
    private boolean verifyDigestCredential(
            PwdCredentialAccount ref,
            DigestCredential credential) {
        if (this.bcryptHashedPassword) {
            LOGGER.error("Digest authentication cannot support bcryped stored "
                    + "password, consider using basic authetication over TLS");
            return false;
        }

        if (ref == null
                || ref.getCredentials() == null
                || ref.getCredentials().getPassword() == null
                || ref.getPrincipal() == null
                || ref.getPrincipal().getName() == null
                || credential == null) {
            return false;
        }

        try {
            MessageDigest digest = credential.getAlgorithm().getMessageDigest();

            digest.update(ref.getPrincipal().getName().getBytes(UTF_8));
            digest.update((byte) ':');
            digest.update(credential.getRealm().getBytes(UTF_8));
            digest.update((byte) ':');
            digest.update(new String(ref.getCredentials().getPassword()).getBytes(UTF_8));

            byte[] ha1 = HexConverter.convertToHexBytes(digest.digest());

            return credential.verifyHA1(ha1);
        } catch (NoSuchAlgorithmException ne) {
            LOGGER.error(ne.getMessage(), ne);
            return false;
        } catch (UnsupportedEncodingException usc) {
            LOGGER.error(usc.getMessage(), usc);
            return false;
        }
    }

    @Override
    public Account verify(Credential credential) {
        return null;
    }

    static boolean checkPassword(String username,
            boolean hashed,
            char[] password,
            char[] expected) {
        if (hashed) {
            if (username == null || password == null || expected == null) {
                return false;
            }
            
            var _password = new String(password);
            var _expected = new String(expected);
            
            // speedup bcrypted pwd check if already checked.
            // bcrypt check is very CPU intensive by design.
            var _cachedPwd = USERS_PWDS_CACHE.get(username.concat(_expected));

            if (_cachedPwd != null
                    && _cachedPwd.isPresent()
                    && _cachedPwd.get().equals(_password)) {
                return true;
            }

            try {
                boolean check = BCrypt.checkpw(_password, _expected);

                if (check) {
                    USERS_PWDS_CACHE.put(username.concat(_expected), _password);
                    return true;
                } else {
                    return false;
                }
            } catch (Throwable t) {
                USERS_PWDS_CACHE.invalidate(username.concat(_expected));
                LOGGER.warn("Error checking bcryped pwd hash", t);
                return false;
            }
        } else {
            return Arrays.equals(password, expected);
        }
    }

    private PwdCredentialAccount getAccount(String id) {
        if (USERS_CACHE == null) {
            return findAccount(id);
        } else {
            Optional<PwdCredentialAccount> _account = USERS_CACHE.getLoading(id);

            if (_account != null && _account.isPresent()) {
                return _account.get();
            } else {
                return null;
            }
        }
    }

    /**
     * Override this method to trasform the account id. By default it returns
     * the id without any transformation. For example, it could be overridden to
     * force the id to be lowercase.
     *
     * @param id the account id
     * @return the trasformed account Id (default is identity)
     */
    protected String accountIdTrasformer(final String id) {
        return id;
    }

    /**
     * check if specified user collection exists; if not, create it
     *
     * @return true if user collection exists or has been created
     */
    private boolean checkUserCollection() {
        var ajp = "ajp".equalsIgnoreCase(restheartBaseUrl.getScheme());

        try {
            var dbStatus = ajp
                    ? Client.getInstance().execute(new Request(
                            Request.METHOD.GET, dbUrl.resolve(dbUrl.getPath()
                                    .concat("/_size")))
                            .header(getXForwardedAccountIdHeaderName().toString(),
                                    X_FORWARDED_ACCOUNT_ID)
                            .header(getXForwardedRolesHeaderName().toString(),
                                    X_FORWARDED_ROLE)).getStatusCode()
                    : Unirest.get(dbUrl.resolve(dbUrl.getPath().concat("/_size"))
                            .toString())
                            .header(getXForwardedAccountIdHeaderName().toString(),
                                    X_FORWARDED_ACCOUNT_ID)
                            .header(getXForwardedRolesHeaderName().toString(),
                                    X_FORWARDED_ROLE)
                            .asString()
                            .getStatus();

            if (dbStatus == HttpStatus.SC_NOT_FOUND) {
                if (ajp) {
                    Client.getInstance().execute(new Request(
                            Request.METHOD.PUT, dbUrl)
                            .header(getXForwardedAccountIdHeaderName().toString(),
                                    X_FORWARDED_ACCOUNT_ID)
                            .header(getXForwardedRolesHeaderName().toString(),
                                    X_FORWARDED_ROLE));

                    LOGGER.info("Users db created");
                    Client.getInstance().execute(new Request(
                            Request.METHOD.PUT, collUrl)
                            .header(getXForwardedAccountIdHeaderName().toString(),
                                    X_FORWARDED_ACCOUNT_ID)
                            .header(getXForwardedRolesHeaderName().toString(),
                                    X_FORWARDED_ROLE));
                    LOGGER.info("Users collection created");
                } else {
                    Unirest.put(dbUrl.toString())
                            .header(getXForwardedAccountIdHeaderName().toString(),
                                    X_FORWARDED_ACCOUNT_ID)
                            .header(getXForwardedRolesHeaderName().toString(),
                                    X_FORWARDED_ROLE)
                            .asString();
                    LOGGER.info("Users db created");
                    Unirest.put(collUrl.toString())
                            .header(getXForwardedAccountIdHeaderName().toString(),
                                    X_FORWARDED_ACCOUNT_ID)
                            .header(getXForwardedRolesHeaderName().toString(),
                                    X_FORWARDED_ROLE)
                            .asString();
                    LOGGER.info("Users collection created");
                }

                return true;
            } else if (dbStatus == HttpStatus.SC_OK) {
                if (ajp) {
                    var collStatus = Client.getInstance().execute(new Request(
                            Request.METHOD.GET, collUrl
                                    .resolve(collUrl.getPath().concat("/_size")))
                            .header(getXForwardedAccountIdHeaderName().toString(),
                                    X_FORWARDED_ACCOUNT_ID)
                            .header(getXForwardedRolesHeaderName().toString(),
                                    X_FORWARDED_ROLE))
                            .getStatusCode();

                    if (collStatus == HttpStatus.SC_NOT_FOUND) {
                        Client.getInstance().execute(new Request(
                                Request.METHOD.PUT, collUrl)
                                .header(getXForwardedAccountIdHeaderName().toString(),
                                        X_FORWARDED_ACCOUNT_ID)
                                .header(getXForwardedRolesHeaderName().toString(),
                                        X_FORWARDED_ROLE));
                        LOGGER.info("Users collection created");
                    }
                } else {
                    var collStatus = Unirest.get(collUrl
                            .resolve(collUrl.getPath().concat("/_size"))
                            .toString())
                            .asString()
                            .getStatus();

                    if (collStatus == HttpStatus.SC_NOT_FOUND) {
                        Unirest.put(collUrl.toString())
                                .header(getXForwardedAccountIdHeaderName().toString(),
                                        X_FORWARDED_ACCOUNT_ID)
                                .header(getXForwardedRolesHeaderName().toString(),
                                        X_FORWARDED_ROLE)
                                .asString();
                        LOGGER.info("Users collection created");
                    }
                }

                return true;
            }

            return false;
        } catch (UnirestException | IOException ure) {
            return false;
        }
    }

    private int countAccounts() {
        var ajp = "ajp".equalsIgnoreCase(restheartBaseUrl.getScheme());

        JsonElement body;

        try {
            if (ajp) {
                var resp = Client.getInstance().execute(new Request(
                        Request.METHOD.GET, collUrl
                                .resolve(collUrl.getPath().concat("/_size")))
                        .header(getXForwardedAccountIdHeaderName().toString(),
                                X_FORWARDED_ACCOUNT_ID)
                        .header(getXForwardedRolesHeaderName().toString(),
                                X_FORWARDED_ROLE));

                if (resp.getStatusCode() == HttpStatus.SC_OK) {
                    body = resp.getBodyAsJson();
                } else {
                    LOGGER.error("Error counting accounts; "
                            + "response status code {}", resp.getStatus());
                    return 1;
                }
            } else {
                var resp = Unirest
                        .get(collUrl.resolve(collUrl.getPath().concat("/_size"))
                                .toString())
                        .header(getXForwardedAccountIdHeaderName().toString(),
                                X_FORWARDED_ACCOUNT_ID)
                        .header(getXForwardedRolesHeaderName().toString(),
                                X_FORWARDED_ROLE)
                        .asString();

                if (resp.getStatus() == HttpStatus.SC_OK) {
                    body = JsonParser.parseString(resp.getBody());
                } else {
                    LOGGER.error("Error counting accounts; "
                            + "response status code {}", resp.getStatus());
                    return 1;
                }
            }

            if (body.isJsonObject()
                    && body.getAsJsonObject().keySet().contains("_size")
                    && body.getAsJsonObject().get("_size").isJsonPrimitive()
                    && body.getAsJsonObject().get("_size").getAsJsonPrimitive().isNumber()) {

                return body.getAsJsonObject().get("_size")
                        .getAsJsonPrimitive()
                        .getAsInt();
            } else {
                LOGGER.error("No _size property in the response "
                        + "of count accounts");
                return 1;
            }

        } catch (UnirestException | IOException | JsonParseException ex) {
            LOGGER.error("Error counting account", ex);
            return 1;
        }

    }

    private void createDefaultAccount() {
        var ajp = "ajp".equalsIgnoreCase(restheartBaseUrl.getScheme());

        try {
            if (ajp) {
                var resp = Client.getInstance().execute(new Request(
                        Request.METHOD.POST, collUrl)
                        .header("content-type", "application/json")
                        .header(getXForwardedAccountIdHeaderName().toString(),
                                X_FORWARDED_ACCOUNT_ID)
                        .header(getXForwardedRolesHeaderName().toString(),
                                X_FORWARDED_ROLE)
                        .body(this.createUserDocument));

                if (resp.getStatusCode() == HttpStatus.SC_CREATED) {
                    return;
                } else {
                    LOGGER.error("Error creating default account; "
                            + "response status code {}", resp.getStatus());
                }
            } else {
                var resp = Unirest.post(collUrl.toString())
                        .header("content-type", "application/json")
                        .header(getXForwardedAccountIdHeaderName().toString(),
                                X_FORWARDED_ACCOUNT_ID)
                        .header(getXForwardedRolesHeaderName().toString(),
                                X_FORWARDED_ROLE)
                        .body(this.createUserDocument)
                        .asString();

                if (resp.getStatus() == HttpStatus.SC_CREATED) {
                    return;
                } else {
                    LOGGER.error("Error creating default account; "
                            + "response status code {}", resp.getStatus());
                }
            }
        } catch (UnirestException | IOException ex) {
            LOGGER.error("Error creating default account", ex);
            return;
        }

    }

    private PwdCredentialAccount findAccount(final String accountId) {
        final String transformedId = accountIdTrasformer(accountId);

        var ajp = "ajp".equalsIgnoreCase(restheartBaseUrl.getScheme());

        String accounts;

        try {
            if (ajp) {
                var resp = Client.getInstance().execute(new Request(
                        Request.METHOD.GET, collUrl)
                        .header(getXForwardedAccountIdHeaderName().toString(),
                                X_FORWARDED_ACCOUNT_ID)
                        .header(getXForwardedRolesHeaderName().toString(),
                                X_FORWARDED_ROLE)
                        .parameter("np", "true")
                        .parameter("filter", "{\""
                                .concat(this.propId)
                                .concat("\":\"")
                                .concat(transformedId)
                                .concat("\"}"))
                        .parameter("pagesize", "" + 1)
                        .parameter("rep", "STANDARD"));

                if (resp.getStatusCode() != HttpStatus.SC_OK) {
                    LOGGER.warn("Wrong response finding the account with id: {}. "
                            + "Response status: {}",
                            transformedId,
                            resp.getStatus());
                    return null;
                } else {
                    accounts = resp.getBody();
                }
            } else {
                var resp = Unirest.get(collUrl.toString())
                        .header(getXForwardedAccountIdHeaderName().toString(),
                                X_FORWARDED_ACCOUNT_ID)
                        .header(getXForwardedRolesHeaderName().toString(),
                                X_FORWARDED_ROLE)
                        .queryString("np", true)
                        .queryString("filter", "{\""
                                .concat(this.propId)
                                .concat("\":\"")
                                .concat(transformedId)
                                .concat("\"}"))
                        .queryString("pagesize", 1)
                        .queryString("rep", "STANDARD")
                        .asString();

                if (resp.getStatus() != HttpStatus.SC_OK) {
                    LOGGER.warn("Wrong response finding the account with id: {}. "
                            + "Response status: {}",
                            transformedId,
                            resp.getStatus());
                    return null;
                } else {
                    accounts = resp.getBody();
                }
            }
        } catch (UnirestException | IOException ex) {
            LOGGER.warn("Error requesting {}: {}", collUrl, ex.getMessage());
            return null;
        }

        JsonElement account;

        try {
            account = JsonPath.read(accounts, "$.[0]");
        } catch (IllegalArgumentException | PathNotFoundException pnfe) {
            LOGGER.warn("Cannot find account {}", accountId);
            return null;
        }

        if (!account.isJsonObject()) {
            LOGGER.warn("Retrived document for account {} is not an object", accountId);
            return null;
        }

        JsonElement _password;

        try {
            _password = JsonPath.read(account, "$.".concat(this.propPassword));
        } catch (PathNotFoundException pnfe) {
            LOGGER.warn("Cannot find pwd property '{}' for account {}",
                    this.propPassword,
                    accountId);
            return null;
        }

        if (!_password.isJsonPrimitive()
                || !_password.getAsJsonPrimitive().isString()) {
            LOGGER.warn("Pwd property of account {} is not a string", accountId);
            return null;
        }

        JsonElement _roles;

        try {
            _roles = JsonPath.read(account, this.jsonPathRoles);
        } catch (PathNotFoundException pnfe) {
            LOGGER.warn("Account with id: {} does not have roles");
            _roles = new JsonArray();
        }

        if (!_roles.isJsonArray()) {
            LOGGER.warn("Roles property of account {} is not an array", accountId);
            return null;
        }

        var roles = new LinkedHashSet();

        _roles.getAsJsonArray().forEach(role -> {
            if (role != null && role.isJsonPrimitive()
                    && role.getAsJsonPrimitive().isString()) {
                roles.add(role.getAsJsonPrimitive().getAsString());
            } else {
                LOGGER.warn("A role of account {} is not a string", accountId);
            }
        });

        return new PwdCredentialAccount(transformedId,
                _password.getAsJsonPrimitive().getAsString().toCharArray(),
                roles);
    }

    /**
     * if client authenticates passing the real credentials, update the account
     * in the auth-token cache, otherwise the client authenticating with the
     * auth-token will not see roles updates until the cache expires (by default
     * TTL is 15 minutes after last request)
     *
     * @param account
     */
    private void updateAuthTokenCache(PwdCredentialAccount account) {
        try {
            var tm = PluginsRegistry.getInstance().getTokenManager();

            if (tm != null) {
                var token = tm.get(account);

                if (token != null) {
                    tm.update(account);
                }
            }
        } catch (ConfigurationException pce) {
            LOGGER.warn("error getting the token manager", pce);
        }
    }
}
