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

import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.restheart.exchange.BsonRequest;
import org.restheart.exchange.BsonResponse;
import org.restheart.plugins.BsonService;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.utils.HttpStatus;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
@RegisterPlugin(
        name = "istatus", 
        description = "Internal service that allows querying for server status",
        defaultURI = "/_internal/status")
public class LicenseStatusService implements BsonService {
    @Override
    public void handle(BsonRequest request, BsonResponse response) throws Exception {
        if (request.isOptions()) {
            handleOptions(request);
        } else if (request.isGet()) {
            if (checkRole(request.getExchange())) {
                var status = CommLicense.getStatus();

                if (status == null) {
                    response.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
                } else {
                    response.setStatusCode(HttpStatus.SC_OK);
                    response.setContent(new BsonDocument("status",
                            new BsonString(status.name())));
                }
            } else {
                response.setStatusCode(HttpStatus.SC_FORBIDDEN);
            }
        } else {
            response.setStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
        }
    }

    private boolean checkRole(HttpServerExchange exchange) {
        var roles = exchange.getRequestHeaders()
                .get(HttpString.tryFromString("X-Forwarded-Account-Roles"));

        if (roles != null && !roles.isEmpty()) {
            for (String role : roles) {
                if ("RESTHeart".equals(role)) {
                    return true;
                }
            }
        }
        return false;
    }
}
