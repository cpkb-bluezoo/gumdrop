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
}
