/*
 * OffloadingSynchronousRealm.java
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

package org.bluezoo.gumdrop.testsupport;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.StorageExecutor;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.auth.RealmCallback;
import org.bluezoo.gumdrop.auth.SaslMechanism;
import org.bluezoo.gumdrop.auth.SynchronousRealm;

/**
 * Test realm whose slow credential checks ({@code passwordMatch} and
 * {@code getScramCredentials}) are deliberately expensive, and which, once
 * bound to a loop, runs them on the {@link StorageExecutor} and completes on
 * the loop - the contract {@code BasicRealm} implements for production.
 * Subclasses write the checks as plain blocking code.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public abstract class OffloadingSynchronousRealm implements SynchronousRealm {

    @Override
    public Realm forSelectorLoop(final SelectorLoop loop) {
        final OffloadingSynchronousRealm outer = this;
        return new SynchronousRealm() {
            @Override
            public Set<SaslMechanism> getSupportedSASLMechanisms() {
                return outer.getSupportedSASLMechanisms();
            }

            @Override
            public boolean passwordMatch(String username, String password) {
                return outer.passwordMatch(username, password);
            }

            @Override
            public String getDigestHA1(String username, String realmName) {
                return outer.getDigestHA1(username, realmName);
            }

            @Override
            public boolean isUserInRole(String username, String role) {
                return outer.isUserInRole(username, role);
            }

            @Override
            public Realm.ScramCredentials getScramCredentials(String username) {
                return outer.getScramCredentials(username);
            }

            @Override
            public void passwordMatch(final String username, final String password,
                    RealmCallback<Boolean> callback) {
                offload(loop, new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return Boolean.valueOf(outer.passwordMatch(username, password));
                    }
                }, callback);
            }

            @Override
            public void getScramCredentials(final String username,
                    RealmCallback<Realm.ScramCredentials> callback) {
                offload(loop, new Callable<Realm.ScramCredentials>() {
                    @Override
                    public Realm.ScramCredentials call() {
                        return outer.getScramCredentials(username);
                    }
                }, callback);
            }
        };
    }

    private static <T> void offload(final SelectorLoop loop, Callable<T> work,
            final RealmCallback<T> callback) {
        Gumdrop gumdrop = loop.getGumdrop();
        gumdrop.getStorageExecutor().submit(new Executor() {
            @Override
            public void execute(Runnable task) {
                loop.invokeLater(task);
            }
        }, work, new StorageExecutor.Callback<T>() {
            @Override
            public void completed(T result) {
                callback.completed(result);
            }

            @Override
            public void failed(Throwable cause) {
                callback.failed(cause);
            }
        });
    }
}
