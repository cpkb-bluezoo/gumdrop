/*
 * BasicRealmLoopBoundTest.java
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

package org.bluezoo.gumdrop.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.testsupport.TestGumdrop;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A {@link BasicRealm} bound to a loop never does slow work on the loop: a
 * hashed-password check and a first SCRAM derivation go to the storage
 * executor and complete on the loop, while cheap lookups answer inline.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class BasicRealmLoopBoundTest {

    private TestGumdrop.QueuedExecutor work;
    private Gumdrop gumdrop;
    private BasicRealm realm;

    @Before
    public void setUp() throws Exception {
        work = new TestGumdrop.QueuedExecutor();
        gumdrop = TestGumdrop.create(work);
        realm = new BasicRealm();
        realm.passwords.put("plain", "secret");
        realm.passwords.put("hashed", BasicRealm.createPbkdf2Hash("secret", new byte[16], 1000));
    }

    @After
    public void tearDown() throws Exception {
        if (gumdrop != null && gumdrop.isStarted()) {
            gumdrop.shutdown();
        }
    }

    private Realm bound() {
        return realm.forSelectorLoop(gumdrop.nextWorkerLoop());
    }

    @Test
    public void plaintextPasswordCheckAnswersInline() {
        CapturedCallback<Boolean> cb = new CapturedCallback<Boolean>();
        bound().passwordMatch("plain", "secret", cb);
        assertTrue(cb.get());
        assertEquals(0, work.pendingCount());
    }

    @Test
    public void hashedPasswordCheckIsOffloaded() throws Exception {
        CapturedCallback<Boolean> cb = new CapturedCallback<Boolean>();
        bound().passwordMatch("hashed", "secret", cb);
        assertFalse("slow check must not run on the caller", cb.isDone());
        assertEquals(1, work.pendingCount());
        work.runAll();
        assertTrue(cb.awaitDone(5000));
        assertTrue(cb.get());
    }

    @Test
    public void wrongHashedPasswordIsRejectedAfterOffload() throws Exception {
        CapturedCallback<Boolean> cb = new CapturedCallback<Boolean>();
        bound().passwordMatch("hashed", "wrong", cb);
        work.runAll();
        assertTrue(cb.awaitDone(5000));
        assertFalse(cb.get());
    }

    @Test
    public void firstScramDerivationIsOffloadedThenCached() throws Exception {
        CapturedCallback<Realm.ScramCredentials> first = new CapturedCallback<Realm.ScramCredentials>();
        Realm bound = bound();
        bound.getScramCredentials("plain", first);
        assertFalse(first.isDone());
        assertEquals(1, work.pendingCount());
        work.runAll();
        assertTrue(first.awaitDone(5000));
        assertNotNull(first.get());

        CapturedCallback<Realm.ScramCredentials> second = new CapturedCallback<Realm.ScramCredentials>();
        bound.getScramCredentials("plain", second);
        assertTrue("cached credentials answer inline", second.isDone());
        assertEquals(0, work.pendingCount());
    }
}
