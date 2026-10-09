/*
 * DefaultFtpHandlerResultsTest.java
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

package org.bluezoo.gumdrop.ftp.server;

import java.nio.ByteBuffer;

import org.junit.Test;

import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;

import static org.junit.Assert.*;

/**
 * Drives every authentication result through the login, password and
 * account stages of {@link DefaultFtpHandler} and checks which state
 * transition each one produces.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultFtpHandlerResultsTest {

    /** Handler whose authentication outcome is scripted. */
    private static final class Scripted extends DefaultFtpHandler {
        private final FtpAuthenticationResult result;

        Scripted(FtpAuthenticationResult result) {
            super(null, null);
            this.result = result;
        }

        @Override
        public void evaluateAuthentication(String username,
                String password, String account,
                org.bluezoo.gumdrop.auth.RealmCallback<FtpAuthenticationResult> callback) {
            callback.completed(result);
        }
    }

    /** Records the name of the state transition invoked. */
    private static final class Recorder implements LoginState, PasswordState,
            AccountState, TlsLoginState, ConnectedState {
        String last;

        @Override
        public void needPassword(PasswordHandler handler) {
            last = "needPassword";
        }

        @Override
        public void needAccount(AccountHandler handler) {
            last = "needAccount";
        }

        @Override
        public void loggedIn(AuthenticatedHandler handler) {
            last = "loggedIn";
        }

        @Override
        public void rejectInvalidUser() {
            last = "rejectInvalidUser";
        }

        @Override
        public void rejectAnonymousNotAllowed() {
            last = "rejectAnonymousNotAllowed";
        }

        @Override
        public void rejectTooManyAttempts() {
            last = "rejectTooManyAttempts";
        }

        @Override
        public void rejectUserLimitExceeded() {
            last = "rejectUserLimitExceeded";
        }

        @Override
        public void rejectInvalidPassword() {
            last = "rejectInvalidPassword";
        }

        @Override
        public void rejectAccountDisabled() {
            last = "rejectAccountDisabled";
        }

        @Override
        public void commandOk() {
            last = "commandOk";
        }

        @Override
        public void rejectInvalidAccount() {
            last = "rejectInvalidAccount";
        }

        @Override
        public void continueLogin(NotAuthenticatedHandler handler) {
            last = "continueLogin";
        }

        @Override
        public void rejectCertificateLogin() {
            last = "rejectCertificateLogin";
        }

        @Override
        public void acceptConnection(String greeting, NotAuthenticatedHandler handler) {
            last = "acceptConnection:" + greeting;
        }

        @Override
        public void acceptLoggedIn(String greeting, AuthenticatedHandler handler) {
            last = "acceptLoggedIn";
        }

        @Override
        public void rejectConnection() {
            last = "rejectConnection";
        }

        @Override
        public void rejectConnection(String message) {
            last = "rejectConnection:" + message;
        }
    }

    private static String login(FtpAuthenticationResult r) {
        Recorder rec = new Recorder();
        new Scripted(r).user(rec, "bob");
        return rec.last;
    }

    private static String password(FtpAuthenticationResult r) {
        Recorder rec = new Recorder();
        new Scripted(r).password(rec, "pw");
        return rec.last;
    }

    private static String account(FtpAuthenticationResult r) {
        Recorder rec = new Recorder();
        new Scripted(r).account(rec, "acct");
        return rec.last;
    }

    @Test
    public void testLoginStageArms() {
        assertEquals("loggedIn", login(FtpAuthenticationResult.SUCCESS));
        assertEquals("needPassword", login(FtpAuthenticationResult.NEED_PASSWORD));
        assertEquals("needAccount", login(FtpAuthenticationResult.NEED_ACCOUNT));
        assertEquals("rejectInvalidUser", login(FtpAuthenticationResult.INVALID_USER));
        assertEquals("rejectAnonymousNotAllowed",
                login(FtpAuthenticationResult.ANONYMOUS_NOT_ALLOWED));
        assertEquals("rejectTooManyAttempts",
                login(FtpAuthenticationResult.TOO_MANY_ATTEMPTS));
        assertEquals("rejectUserLimitExceeded",
                login(FtpAuthenticationResult.USER_LIMIT_EXCEEDED));
        assertEquals("rejectInvalidUser",
                login(FtpAuthenticationResult.INVALID_PASSWORD));
    }

    @Test
    public void testPasswordStageArms() {
        assertEquals("loggedIn", password(FtpAuthenticationResult.SUCCESS));
        assertEquals("needAccount", password(FtpAuthenticationResult.NEED_ACCOUNT));
        assertEquals("rejectInvalidPassword",
                password(FtpAuthenticationResult.INVALID_PASSWORD));
        assertEquals("rejectAccountDisabled",
                password(FtpAuthenticationResult.ACCOUNT_DISABLED));
        assertEquals("rejectTooManyAttempts",
                password(FtpAuthenticationResult.TOO_MANY_ATTEMPTS));
        assertEquals("rejectInvalidPassword",
                password(FtpAuthenticationResult.INVALID_USER));
    }

    @Test
    public void testAccountStageArms() {
        assertEquals("loggedIn", account(FtpAuthenticationResult.SUCCESS));
        assertEquals("commandOk", account(FtpAuthenticationResult.NEED_ACCOUNT));
        assertEquals("rejectInvalidAccount",
                account(FtpAuthenticationResult.INVALID_ACCOUNT));
        assertEquals("rejectInvalidPassword",
                account(FtpAuthenticationResult.INVALID_USER));
    }

    @Test
    public void testConnectedAndTlsStages() {
        Recorder rec = new Recorder();
        DefaultFtpHandler h = new DefaultFtpHandler(null, null, null, "welcome");
        h.connected(rec, null);
        assertEquals("acceptConnection:welcome", rec.last);
        h.tlsEstablished(rec, null);
        assertEquals("continueLogin", rec.last);
        h.disconnected();
    }

    @Test
    public void testTransferHooksAndSiteCommands() {
        DefaultFtpHandler h = new DefaultFtpHandler(null, null);
        h.transferStarting("/a", true, 3);
        h.transferStarting("/a", false, -1);
        h.transferProgress("/a", true, ByteBuffer.allocate(1), 1024 * 1024);
        h.transferProgress("/a", false, ByteBuffer.allocate(1), 7);
        h.transferCompleted("/a", true, 3, true);
        h.transferCompleted("/a", false, 3, false);
        assertEquals(FtpFileOperationResult.SUCCESS, h.handleSiteCommand("help me"));
        assertEquals(FtpFileOperationResult.NOT_SUPPORTED, h.handleSiteCommand("other"));
    }

}
