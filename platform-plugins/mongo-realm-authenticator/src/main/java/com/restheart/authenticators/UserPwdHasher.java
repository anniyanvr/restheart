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
package com.restheart.authenticators;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.bson.BsonString;
import org.mindrot.jbcrypt.BCrypt;
import org.restheart.exchange.MongoRequest;
import org.restheart.exchange.MongoResponse;
import org.restheart.plugins.InjectPluginsRegistry;
import org.restheart.plugins.InterceptPoint;
import org.restheart.plugins.MongoInterceptor;
import org.restheart.plugins.PluginsRegistry;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.utils.URLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
@RegisterPlugin(name = "userPwdHasher",
        description = "automatically hashes the user password",
        interceptPoint = InterceptPoint.REQUEST_AFTER_AUTH,
        requiresContent = true)
public class UserPwdHasher implements MongoInterceptor {

    static final Logger LOGGER = LoggerFactory.getLogger(UserPwdHasher.class);

    private String usersUri;
    private String propNamePassword;
    private Integer complexity;

    private boolean enabled = false;
    
    UserPwdHasher(String usersUri, String propNamePassword, Integer complexity) {
        this.usersUri = usersUri;
        this.propNamePassword = propNamePassword;
        this.complexity = complexity;
        this.enabled = true;
    }

    @InjectPluginsRegistry
    public void init(PluginsRegistry registry) {
        var _rhAuth = registry.getAuthenticator("rhAuthenticator");

        if (_rhAuth == null || !_rhAuth.isEnabled()) {
            enabled = false;
        } else {
            var rhAuth = (MongoRealmAuthenticator) _rhAuth.getInstance();
            
            this.usersUri = rhAuth.getUsersUri();
            this.propNamePassword = rhAuth.getPropPassword();
            this.complexity = rhAuth.getBcryptComplexity();
            enabled = true;
        }   
    }

    @Override
    public void handle(MongoRequest request, MongoResponse response) throws Exception {
        var content = request.getContent();

        if (content.isArray() && request.isPost()) {
            // POST collection with array of documents
            JsonArray passwords = JsonPath.read(content,
                    "$.[*].".concat(this.propNamePassword));

            int[] iarr = {0};

            passwords.forEach(plain -> {
                if (plain != null && plain.isJsonPrimitive()
                        && plain.getAsJsonPrimitive().isString()) {
                    var hashed = BCrypt.hashpw(
                            plain.getAsJsonPrimitive().getAsString(),
                            BCrypt.gensalt(complexity));

                    content.asArray().get(iarr[0]).asDocument()
                            .put(this.propNamePassword, new BsonString(hashed));
                }

                iarr[0]++;
            });
        } else if (content.isDocument()) {
            // PUT/PATCH document or bulk PATCH
            JsonElement plain;
            try {
                plain = JsonPath.read(content,
                        "$.".concat(this.propNamePassword));

                if (plain != null && plain.isJsonPrimitive()
                        && plain.getAsJsonPrimitive().isString()) {
                    String hashed = BCrypt.hashpw(
                            plain.getAsJsonPrimitive().getAsString(),
                            BCrypt.gensalt(complexity));

                    content.asDocument()
                            .put(this.propNamePassword, new BsonString(hashed));
                }
            } catch (PathNotFoundException pnfe) {
                return;
            }
        }
    }

    @Override
    public boolean resolve(MongoRequest request, MongoResponse response) {
        var requestPath = URLUtils.removeTrailingSlashes(
                request.getPath());

        return enabled
                && (request.isHandledBy("mongo")
                && request.isPost()
                && request.isContentTypeJson()
                && (requestPath.equals(usersUri)
                || requestPath.equals(usersUri.concat("/"))))
                || ((request.isPatch() || request.isPut())
                && (requestPath.startsWith(usersUri.concat("/"))
                && requestPath.length() > usersUri.concat("/").length()));
    }
}
