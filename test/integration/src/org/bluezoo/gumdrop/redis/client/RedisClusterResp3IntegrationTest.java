/*
 * RedisClusterIntegrationTest.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with gumdrop.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.redis.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import org.bluezoo.gumdrop.redis.codec.RespValue;

import static org.junit.Assert.*;

/**
 * {@link RedisClusterClient} negotiating RESP3. Every test of
 * {@link RedisClusterIntegrationTest} is run again with {@code HELLO 3} on
 * every node connection, so routing, redirects, transactions and Pub/Sub
 * are all exercised over RESP3; the tests here check the negotiation itself.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class RedisClusterResp3IntegrationTest extends RedisClusterIntegrationTest {

    @Override
    protected int protocol() {
        return 3;
    }

    private static String field(String info, String name) {
        for (String part : info.trim().split(" ")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        return null;
    }

    @Test
    public void helloThreeIsNegotiatedOnEveryNode() throws Exception {
        // a command naming no key goes to the connected nodes in turn
        Set<String> nodes = new HashSet<String>();
        for (int i = 0; i < 30; i++) {
            List<RespValue> reply = command("CLIENT", "INFO");
            String info = reply.get(0).asString();
            assertEquals(info, "3", field(info, "resp"));
            nodes.add(field(info, "laddr"));
        }
        assertTrue("every master was asked: " + nodes, nodes.size() >= 3);
    }

    @Test
    public void anInvalidationFromClientSideCachingReachesThePushHandler() throws Exception {
        final LinkedBlockingQueue<List<Object>> pushes = new LinkedBlockingQueue<List<Object>>();
        onLoop(new Runnable() {
            @Override
            public void run() {
                session.setPushHandler(new PushHandler() {
                    @Override
                    public void onPush(String kind, List<Object> payload) {
                        if (kind.equals("invalidate")) {
                            pushes.add(payload);
                        }
                    }
                });
            }
        });
        // tracking is per connection; a command naming no key goes to the
        // connections in turn, so a few of them reach every master
        for (int i = 0; i < 12; i++) {
            command("CLIENT", "TRACKING", "ON");
        }
        String key = prefix + "tracked";
        assertEquals("OK", set(key, "1"));
        assertEquals("1", get(key));
        assertEquals("OK", set(key, "2"));
        List<Object> payload = pushes.poll(10, TimeUnit.SECONDS);
        assertNotNull("no invalidation arrived", payload);
        assertTrue(payload.toString(), payload.get(0) instanceof List);
        assertTrue(payload.toString(), ((List<?>) payload.get(0)).contains(key));
    }
}
