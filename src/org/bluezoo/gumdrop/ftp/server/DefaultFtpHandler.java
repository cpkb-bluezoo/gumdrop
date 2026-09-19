/*
 * DefaultFtpHandler.java
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

import org.bluezoo.gumdrop.Endpoint;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;

import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stock staged FTP handler for local filesystem access.
 *
 * <p>Implements the login pipeline ({@link ClientConnected} through
 * {@link AuthenticatedHandler}) with optional {@link Realm} authentication.
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultFtpHandler implements ClientConnected, NotAuthenticatedHandler,
        PasswordHandler, AccountHandler, AuthenticatedHandler, AuthenticatingHandler {

    private static final Logger LOGGER =
            Logger.getLogger(DefaultFtpHandler.class.getName());
    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.ftp.L10N");

    private final FtpFileSystem fileSystem;
    private final Realm realm;
    private final QuotaManager quotaManager;
    private final String welcomeMessage;

    private String pendingUser;
    private String authenticatedUser;

    public DefaultFtpHandler(FtpFileSystem fileSystem, Realm realm,
            QuotaManager quotaManager, String welcomeMessage) {
        this.fileSystem = fileSystem;
        this.realm = realm;
        this.quotaManager = quotaManager;
        this.welcomeMessage = welcomeMessage;
    }

    public DefaultFtpHandler(FtpFileSystem fileSystem, Realm realm) {
        this(fileSystem, realm, null, null);
    }

    @Override
    public void connected(ConnectedState state, Endpoint endpoint) {
        state.acceptConnection(welcomeMessage, this);
    }

    @Override
    public void disconnected() {
        LOGGER.fine(L10N.getString("debug.session_closed"));
    }

    @Override
    public void user(LoginState state, String username) {
        pendingUser = username;
        applyLoginResult(state, evaluateAuthentication(username, null, null));
    }

    @Override
    public void tlsEstablished(TlsLoginState state, SecurityInfo securityInfo) {
        state.continueLogin(this);
    }

    @Override
    public void password(PasswordState state, String password) {
        applyPasswordResult(state,
                evaluateAuthentication(pendingUser, password, null));
    }

    @Override
    public void account(AccountState state, String account) {
        applyAccountResult(state,
                evaluateAuthentication(pendingUser, null, account));
    }

    @Override
    public FtpAuthenticationResult evaluateAuthentication(String username,
            String password, String account) {
        if (username == null || username.trim().isEmpty()) {
            return FtpAuthenticationResult.INVALID_USER;
        }

        if (password == null && account == null) {
            return FtpAuthenticationResult.NEED_PASSWORD;
        }

        try {
            if (realm != null) {
                if (password == null) {
                    return FtpAuthenticationResult.NEED_PASSWORD;
                }
                if (realm.passwordMatch(username.trim(), password)) {
                    authenticatedUser = username.trim();
                    return FtpAuthenticationResult.SUCCESS;
                }
                return FtpAuthenticationResult.INVALID_PASSWORD;
            }

            if (password != null && password.trim().isEmpty()) {
                return FtpAuthenticationResult.INVALID_PASSWORD;
            }
            authenticatedUser = username.trim();
            return FtpAuthenticationResult.SUCCESS;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, L10N.getString("warn.ftp_authentication_error"), e);
            return FtpAuthenticationResult.INVALID_PASSWORD;
        }
    }

    private void applyLoginResult(LoginState state,
            FtpAuthenticationResult result) {
        switch (result) {
            case SUCCESS:
                state.loggedIn(this);
                break;
            case NEED_PASSWORD:
                state.needPassword(this);
                break;
            case NEED_ACCOUNT:
                state.needAccount(this);
                break;
            case INVALID_USER:
                state.rejectInvalidUser();
                break;
            case ANONYMOUS_NOT_ALLOWED:
                state.rejectAnonymousNotAllowed();
                break;
            case TOO_MANY_ATTEMPTS:
                state.rejectTooManyAttempts();
                break;
            case USER_LIMIT_EXCEEDED:
                state.rejectUserLimitExceeded();
                break;
            default:
                state.rejectInvalidUser();
                break;
        }
    }

    private void applyPasswordResult(PasswordState state,
            FtpAuthenticationResult result) {
        switch (result) {
            case SUCCESS:
                state.loggedIn(this);
                break;
            case NEED_ACCOUNT:
                state.needAccount(this);
                break;
            case INVALID_PASSWORD:
                state.rejectInvalidPassword();
                break;
            case ACCOUNT_DISABLED:
                state.rejectAccountDisabled();
                break;
            case TOO_MANY_ATTEMPTS:
                state.rejectTooManyAttempts();
                break;
            default:
                state.rejectInvalidPassword();
                break;
        }
    }

    private void applyAccountResult(AccountState state,
            FtpAuthenticationResult result) {
        switch (result) {
            case SUCCESS:
                state.loggedIn(this);
                break;
            case NEED_ACCOUNT:
                state.commandOk();
                break;
            case INVALID_ACCOUNT:
                state.rejectInvalidAccount();
                break;
            default:
                state.rejectInvalidPassword();
                break;
        }
    }

    @Override
    public FtpFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public QuotaManager getQuotaManager() {
        return quotaManager;
    }

    @Override
    public void transferStarting(String path, boolean upload, long size) {
        String direction = upload ? "upload" : "download";
        String sizeStr = (size >= 0) ? " (" + size + " bytes)" : "";
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_transfer_starting"),
                direction, path, sizeStr, authenticatedUser));
    }

    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data,
            long totalBytesTransferred) {
        if (totalBytesTransferred % (1024 * 1024) == 0) {
            String direction = upload ? "upload" : "download";
            LOGGER.info(MessageFormat.format(
                    L10N.getString("info.simple_ftp_transfer_progress"),
                    direction, path, totalBytesTransferred));
        }
    }

    @Override
    public void transferCompleted(String path, boolean upload,
            long totalBytesTransferred, boolean success) {
        String direction = upload ? "upload" : "download";
        String status = success ? "completed" : "failed";
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_transfer_completed"),
                status, direction, path, totalBytesTransferred,
                authenticatedUser));
    }

    @Override
    public FtpFileOperationResult handleSiteCommand(String command) {
        LOGGER.info(MessageFormat.format(
                L10N.getString("info.simple_ftp_site_command"),
                authenticatedUser, command));
        if (command.toUpperCase().startsWith("HELP")) {
            return FtpFileOperationResult.SUCCESS;
        }
        return FtpFileOperationResult.NOT_SUPPORTED;
    }

    @Override
    public boolean canStore(String username, long bytesToStore) {
        return AuthenticatedHandler.super.canStore(
                authenticatedUser != null ? authenticatedUser : username,
                bytesToStore);
    }

    @Override
    public Quota getQuota(String username) {
        return AuthenticatedHandler.super.getQuota(
                authenticatedUser != null ? authenticatedUser : username);
    }

}
