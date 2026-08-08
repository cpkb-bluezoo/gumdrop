/*
 * AMQPLainClientMechanismTest.java
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

package org.bluezoo.gumdrop.amqp.client;

import org.junit.Test;

import java.nio.ByteBuffer;

import org.bluezoo.gumdrop.auth.SASLClientMechanism;

import static org.junit.Assert.*;

/** Unit tests for the AMQPLAIN SASL mechanism (issue #188). */
public class AMQPLainClientMechanismTest {

    @Test
    public void testMechanismName() {
        SASLClientMechanism mechanism = new AMQPLainClientMechanism("guest", "guest");
        assertEquals("AMQPLAIN", mechanism.getMechanismName());
    }

    @Test
    public void testHasInitialResponse() {
        SASLClientMechanism mechanism = new AMQPLainClientMechanism("guest", "guest");
        assertTrue(mechanism.hasInitialResponse());
    }

    @Test
    public void testInitialResponseIsFieldTableWithLoginAndPassword() throws Exception {
        AMQPLainClientMechanism mechanism = new AMQPLainClientMechanism("alice", "s3cret");
        byte[] response = mechanism.evaluateChallenge(new byte[0]);

        FieldTable decoded = FieldTable.decode(ByteBuffer.wrap(response), response.length);
        assertEquals("alice", decoded.get("LOGIN"));
        assertEquals("s3cret", decoded.get("PASSWORD"));
        assertEquals(2, decoded.size());
    }

    @Test
    public void testResponseMatchesFieldTableEncoding() {
        AMQPLainClientMechanism mechanism = new AMQPLainClientMechanism("alice", "s3cret");
        byte[] response = mechanism.evaluateChallenge(new byte[0]);

        FieldTable expected = new FieldTable().put("LOGIN", "alice").put("PASSWORD", "s3cret");
        ByteBuffer expectedEncoded = expected.encode();
        byte[] expectedBytes = new byte[expectedEncoded.remaining()];
        expectedEncoded.get(expectedBytes);

        assertArrayEquals(expectedBytes, response);
    }

    @Test
    public void testNullPasswordTreatedAsEmpty() throws Exception {
        AMQPLainClientMechanism mechanism = new AMQPLainClientMechanism("alice", null);
        byte[] response = mechanism.evaluateChallenge(new byte[0]);
        FieldTable decoded = FieldTable.decode(ByteBuffer.wrap(response), response.length);
        assertEquals("", decoded.get("PASSWORD"));
    }

    @Test
    public void testCompletesAfterFirstEvaluation() {
        AMQPLainClientMechanism mechanism = new AMQPLainClientMechanism("alice", "s3cret");
        assertFalse(mechanism.isComplete());
        mechanism.evaluateChallenge(new byte[0]);
        assertTrue(mechanism.isComplete());
    }

}
