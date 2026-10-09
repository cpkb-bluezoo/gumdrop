/*
 * IMAPScriptedHandlerQuotaTest.java
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
 * Re-runs the scripted application-handler scenarios of
 * {@link IMAPScriptedHandlerTest} with a quota manager on the listener and
 * an administrator account, so the quota commands reach the application
 * handler and every {@code QuotaState} response (proceed, not supported,
 * explicit quota data, failure) is taken.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class IMAPScriptedHandlerQuotaTest extends IMAPScriptedHandlerTest {

    @Override
    protected void configureListener(ImapListener l) {
        l.realm(new AcceptingRealm("editor", "editor", true));
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();
        qm.defaultQuota("10MB");
        l.quotaManager(qm);
    }

    @Test(timeout = 30000)
    public void testApplicationHandlerSuppliesQuotaRoots() throws Exception {
        login();
        okV(5, "GETQUOTAROOT INBOX");
        assertNotNull(endpoint.findLineContaining("* QUOTAROOT"));
        assertNotNull(endpoint.findLineContaining("* QUOTA user.editor ("));
        okV(0, "SELECT INBOX");
        okV(5, "GETQUOTAROOT INBOX");
        okV(2, "GETQUOTA \"\"");
        assertNotNull(endpoint.findLineContaining("STORAGE 5 100"));
    }
}
