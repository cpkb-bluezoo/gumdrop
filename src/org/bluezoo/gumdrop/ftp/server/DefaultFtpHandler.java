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
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.SecurityInfo;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.ftp.FtpAuthenticationResult;
import org.bluezoo.gumdrop.ftp.FtpFileOperationResult;
import org.bluezoo.gumdrop.ftp.FtpFileSystem;
import org.bluezoo.gumdrop.quota.Quota;
import org.bluezoo.gumdrop.quota.QuotaManager;
import org.bluezoo.gumdrop.telemetry.TelemetryConfig;
import org.bluezoo.gumdrop.telemetry.EventLogger;

import java.nio.ByteBuffer;
import java.util.ResourceBundle;
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

    private Endpoint endpoint;

    private static final Logger LOGGER =
            Logger.getLogger(DefaultFtpHandler.class.getName());

    private EventLogger events() {
        return (endpoint != null ? endpoint.getTelemetryConfig() : new TelemetryConfig()).getLogger(DefaultFtpHandler.class, L10N);
    }
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
        this.endpoint = endpoint;
        state.acceptConnection(welcomeMessage, this);
    }

    @Override
    public void disconnected() {
        LOGGER.fine(L10N.getString("debug.session_closed"));
    }

    @Override
    public void user(final LoginState state, String username) {
        pendingUser = username;
        evaluateAuthentication(username, null, null,
                new RealmCallback<FtpAuthenticationResult>() {
            @Override
            public void completed(FtpAuthenticationResult result) {
                applyLoginResult(state, result);
            }

            @Override
            public void failed(Throwable cause) {
                applyLoginResult(state, FtpAuthenticationResult.INVALID_USER);
            }
        });
    }

    @Override
    public void tlsEstablished(TlsLoginState state, SecurityInfo securityInfo) {
        state.continueLogin(this);
    }

    @Override
    public void password(final PasswordState state, String password) {
        evaluateAuthentication(pendingUser, password, null,
                new RealmCallback<FtpAuthenticationResult>() {
            @Override
            public void completed(FtpAuthenticationResult result) {
                applyPasswordResult(state, result);
            }

            @Override
            public void failed(Throwable cause) {
                applyPasswordResult(state, FtpAuthenticationResult.INVALID_PASSWORD);
            }
        });
    }

    @Override
    public void account(final AccountState state, String account) {
        evaluateAuthentication(pendingUser, null, account,
                new RealmCallback<FtpAuthenticationResult>() {
            @Override
            public void completed(FtpAuthenticationResult result) {
                applyAccountResult(state, result);
            }

            @Override
            public void failed(Throwable cause) {
                applyAccountResult(state, FtpAuthenticationResult.INVALID_PASSWORD);
            }
        });
    }

    @Override
    public void evaluateAuthentication(String username, String password,
            String account, final RealmCallback<FtpAuthenticationResult> callback) {
        if (username == null || username.trim().isEmpty()) {
            callback.completed(FtpAuthenticationResult.INVALID_USER);
            return;
        }

        if (password == null && account == null) {
            callback.completed(FtpAuthenticationResult.NEED_PASSWORD);
            return;
        }

        final String trimmed = username.trim();
        if (realm != null) {
            if (password == null) {
                callback.completed(FtpAuthenticationResult.NEED_PASSWORD);
                return;
            }
            // The realm answers without making this connection's loop wait.
            realm.forSelectorLoop(endpoint != null ? endpoint.getSelectorLoop() : null)
                    .passwordMatch(trimmed, password, new RealmCallback<Boolean>() {
                @Override
                public void completed(Boolean matched) {
                    if (matched != null && matched.booleanValue()) {
                        authenticatedUser = trimmed;
                        callback.completed(FtpAuthenticationResult.SUCCESS);
                    } else {
                        callback.completed(FtpAuthenticationResult.INVALID_PASSWORD);
                    }
                }

                @Override
                public void failed(Throwable cause) {
                    events().warn("warn.ftp_authentication_error").thrown(cause).emit();
                    callback.completed(FtpAuthenticationResult.INVALID_PASSWORD);
                }
            });
            return;
        }

        if (password != null && password.trim().isEmpty()) {
            callback.completed(FtpAuthenticationResult.INVALID_PASSWORD);
            return;
        }
        authenticatedUser = trimmed;
        callback.completed(FtpAuthenticationResult.SUCCESS);
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
        events().info("info.simple_ftp_transfer_starting")
                .attr("direction", direction)
                .attr("path", path)
                .attr("size_str", sizeStr)
                .attr("authenticated_user", authenticatedUser).emit();
    }

    @Override
    public void transferProgress(String path, boolean upload, ByteBuffer data,
            long totalBytesTransferred) {
        if (totalBytesTransferred % (1024 * 1024) == 0) {
            String direction = upload ? "upload" : "download";
            events().info("info.simple_ftp_transfer_progress")
                    .attr("direction", direction)
                    .attr("path", path)
                    .attr("total_bytes_transferred", totalBytesTransferred).emit();
        }
    }

    @Override
    public void transferCompleted(String path, boolean upload,
            long totalBytesTransferred, boolean success) {
        String direction = upload ? "upload" : "download";
        String status = success ? "completed" : "failed";
        events().info("info.simple_ftp_transfer_completed")
                .attr("status", status)
                .attr("direction", direction)
                .attr("path", path)
                .attr("total_bytes_transferred", totalBytesTransferred)
                .attr("authenticated_user", authenticatedUser).emit();
    }

    @Override
    public FtpFileOperationResult handleSiteCommand(String command) {
        events().info("info.simple_ftp_site_command")
                .attr("authenticated_user", authenticatedUser)
                .attr("command", command).emit();
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
