/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.handlers.stream;

import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.undertow.websockets.core.WebSocketChannel;
import java.util.HashSet;
import java.util.Set;
import org.bson.Document;

/**
 *
 * @author omartrasatti
 */
public class CacheableChangeStreamCursor {

    private MongoCursor<ChangeStreamDocument<Document>> iterator = null;
    private Set<WebSocketChannel> sessions = new HashSet<>();

    public CacheableChangeStreamCursor(MongoCursor<ChangeStreamDocument<Document>> iterator) {
        this.iterator = iterator;
    }
    
    public MongoCursor<ChangeStreamDocument<Document>> getIterator() {
        return this.iterator;
    }

    public void addSession(WebSocketChannel channel) {
        this.sessions.add(channel);
    }
    
    public Set<WebSocketChannel> getSessions() {
        return this.sessions;
    }
}