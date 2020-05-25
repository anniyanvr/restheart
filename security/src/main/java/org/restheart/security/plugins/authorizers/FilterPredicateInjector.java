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
package org.restheart.security.plugins.authorizers;

import java.util.ArrayDeque;
import org.bson.BsonDocument;
import org.restheart.exchange.MongoRequest;
import org.restheart.exchange.MongoResponse;
import org.restheart.plugins.InjectPluginsRegistry;
import org.restheart.plugins.InterceptPoint;
import org.restheart.plugins.MongoInterceptor;
import org.restheart.plugins.PluginsRegistry;
import org.restheart.plugins.RegisterPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RegisterPlugin(name = "filterPredicateInjector",
        description = "inject the filter set by ACL into the request",
        interceptPoint = InterceptPoint.REQUEST_AFTER_AUTH)
public class FilterPredicateInjector implements MongoInterceptor {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(FilterPredicateInjector.class);

    private boolean enabled = false;

    @InjectPluginsRegistry
    public void init(PluginsRegistry registry) {
        var __maa = registry.getAuthorizers()
                .stream()
                .filter(a -> "mongoAclAuthorizer".equals(a.getName()))
                .findFirst();

        if (__maa == null || !__maa.isPresent()) {
            enabled = false;
        } else if (__maa.get().isEnabled()) {
            enabled = true;
        } else {
            enabled = false;
        }
    }

    @Override
    public void handle(MongoRequest request, MongoResponse response) throws Exception {
        var exchange = request.getExchange();
        var predicate = FilterPredicate.from(exchange);

        if (request.isGet()
                && predicate.getReadFilter() != null) {
            LOGGER.debug("read filter: {}", predicate.getReadFilter());
            addFilter(request, predicate.getReadFilter());
        } else if ((request.isPatch()
                || request.isPut()
                || request.isPost()
                || request.isDelete())
                && predicate.getWriteFilter() != null) {
            LOGGER.debug("write filter to add: {}", predicate.getWriteFilter());
            addFilter(request, predicate.getWriteFilter());
        } else {
            LOGGER.trace("predicate specifies no filter");
        }
    }

    @Override
    public boolean resolve(MongoRequest request, MongoResponse response) {
        return enabled
                && request.isHandledBy("mongo")
                && FilterPredicate.from(request.getExchange()) != null;
    }

    private void addFilter(final MongoRequest request, final BsonDocument filter) {
        if (filter == null) {
            return;
        }

        // this resolve the filter against the current exchange
        // eg {'username':'%u'} => {'username':'uji'}
        var resolvedFilter = FilterPredicate
                .interpolateFilterVars(request.getExchange(), filter);

        if (request.getFilter() == null) {
            request.setFilter(new ArrayDeque<>());
        }

        request.getFilter().add(resolvedFilter.toString());
    }
}
