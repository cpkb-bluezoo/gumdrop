/*
 * IMAPSessionQuotaTest.java
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

package org.bluezoo.gumdrop.imap;

import org.junit.Test;

import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;

import static org.junit.Assert.*;

/**
 * Re-runs the {@link IMAPSessionCoverageTest} scenarios with a quota manager
 * on the listener and an administrator account, then drives GETQUOTA,
 * GETQUOTAROOT and SETQUOTA through their success, permission, syntax and
 * disabled-extension branches.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPSessionQuotaTest extends IMAPSessionCoverageTest {

    @Override
    protected void configureListener(ImapListener l) {
        l.setRealm(new AcceptingRealm("editor", "editor", true));
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();
        qm.setDefaultQuota("10MB");
        l.setQuotaManager(qm);
    }

    @Test(timeout = 30000)
    public void testGetQuotaVariants() throws Exception {
        login();
        ok("GETQUOTA \"\"");
        assertNotNull(endpoint.findLineContaining("* QUOTA"));
        ok("GETQUOTA \"user.editor\"");
        ok("GETQUOTA user.editor");
        ok("GETQUOTA \"user.someoneelse\"");
        ok("GETQUOTAROOT INBOX");
        assertNotNull(endpoint.findLineContaining("* QUOTAROOT"));
        bad("GETQUOTAROOT");
        bad("GETQUOTAROOT \"\"");
        ok("SELECT INBOX");
        ok("GETQUOTA \"\"");
        ok("GETQUOTAROOT INBOX");
        ok("CLOSE");
    }

    @Test(timeout = 30000)
    public void testSetQuotaVariants() throws Exception {
        login();
        ok("SETQUOTA \"user.editor\" (STORAGE 2048)");
        String line = endpoint.findLineContaining("* QUOTA");
        assertNotNull(line);
        assertTrue(line, line.contains("STORAGE"));
        ok("SETQUOTA \"user.editor\" (MESSAGE 50)");
        ok("SETQUOTA \"user.editor\" (STORAGE 512 MESSAGE 25)");
        ok("SETQUOTA \"user.editor\" (STORAGE 512 UNKNOWN 7)");
        ok("SETQUOTA \"user.editor\" ()");
        ok("SETQUOTA \"user.other\" (STORAGE 100)");
        ok("GETQUOTA \"user.editor\"");
        bad("SETQUOTA \"user.editor\" STORAGE 100");
        bad("SETQUOTA \"user.editor\" (STORAGE)");
        bad("SETQUOTA \"user.editor\" (STORAGE abc)");
        ok("SELECT INBOX");
        ok("SETQUOTA \"user.editor\" (STORAGE 4096 MESSAGE 99)");
        bad("SETQUOTA \"user.editor\" (STORAGE)");
    }

    @Test(timeout = 30000)
    public void testNonAdministratorIsDenied() throws Exception {
        listener.setRealm(new AcceptingRealm("editor", "editor", false));
        login();
        ok("GETQUOTA \"user.editor\"");
        no("GETQUOTA \"user.someoneelse\"");
        no("SETQUOTA \"user.editor\" (STORAGE 100)");
    }

    @Test(timeout = 30000)
    public void testQuotaDisabledOnListener() throws Exception {
        listener.setEnableQUOTA(false);
        login();
        bad("GETQUOTA \"\"");
        bad("GETQUOTAROOT INBOX");
        bad("SETQUOTA \"\" (STORAGE 1)");
    }

    @Test(timeout = 30000)
    public void testQuotaManagerMissing() throws Exception {
        listener.setQuotaManager(null);
        login();
        no("GETQUOTA \"\"");
        no("GETQUOTAROOT INBOX");
        no("SETQUOTA \"\" (STORAGE 1)");
    }
}
