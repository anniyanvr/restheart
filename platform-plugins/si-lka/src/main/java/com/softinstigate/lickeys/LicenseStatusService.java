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
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.restheart.handlers.RequestContext;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.plugins.Service;
import org.restheart.utils.HttpStatus;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
@RegisterPlugin(name = "istatus", description = "Internal service that allows querying for server status")
public class LicenseStatusService extends Service {
    public LicenseStatusService(Map<String, Object> confArgs) {
        super(confArgs);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, RequestContext context) throws Exception {
        if (context.isOptions()) {
            handleOptions(exchange, context);
        } else if (context.isGet()) {
            if (checkRole(exchange)) {
                var status = CommLicense.getStatus();

                if (status == null) {
                    context.setResponseStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
                } else {
                    context.setResponseStatusCode(HttpStatus.SC_OK);
                    context.setResponseContent(new BsonDocument("status",
                            new BsonString(status.name())));
                }
            } else {
                context.setResponseStatusCode(HttpStatus.SC_FORBIDDEN);
            }
        } else {
            context.setResponseStatusCode(HttpStatus.SC_NOT_IMPLEMENTED);
        }

        next(exchange, context);
    }

    @Override
    public String defaultUri() {
        return "/_internal/status";
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
