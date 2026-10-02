/*
 * DefaultIMAPHandlerAllMethodsTest.java
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

package org.bluezoo.gumdrop.imap.server;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import org.bluezoo.gumdrop.imap.StatusItem;
import org.bluezoo.gumdrop.mailbox.Flag;
import org.bluezoo.gumdrop.mailbox.MessageSet;
import org.bluezoo.gumdrop.mailbox.SearchCriteria;
import org.bluezoo.gumdrop.mailbox.StoreAction;
import org.bluezoo.gumdrop.quota.RoleBasedQuotaManager;

import static org.junit.Assert.*;

/**
 * Exercises every callback of {@link DefaultIMAPHandler}, which must
 * authorise each request by calling {@code proceed} on the supplied state.
 * State objects are dynamic proxies recording the invoked method name.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DefaultIMAPHandlerAllMethodsTest {

    private static final class Recorder implements InvocationHandler {
        final List<String> calls = new ArrayList<String>();
        final List<Object> firstArgs = new ArrayList<Object>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            calls.add(method.getName());
            if (args != null && args.length > 0) {
                firstArgs.add(args[0]);
            }
            return null;
        }
    }

    private static <T> T proxy(Class<T> type, Recorder rec) {
        Object o = Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[] { type }, rec);
        return type.cast(o);
    }

    private final DefaultIMAPHandler handler = new DefaultIMAPHandler();

    private static void assertProceeded(Recorder rec, DefaultIMAPHandler h) {
        assertEquals(Collections.singletonList("proceed"), rec.calls);
        assertSame(h, rec.firstArgs.get(0));
    }

    @Test
    public void testDisconnectedIsNoOp() {
        handler.disconnected();
    }

    @Test
    public void testAuthenticatedCallbacks() {
        Recorder r = new Recorder();
        handler.examine(proxy(SelectState.class, r), null, "INBOX");
        assertProceeded(r, handler);

        r = new Recorder();
        handler.create(proxy(CreateState.class, r), null, "X");
        assertProceeded(r, handler);

        r = new Recorder();
        handler.delete(proxy(DeleteState.class, r), null, "X");
        assertProceeded(r, handler);

        r = new Recorder();
        handler.rename(proxy(RenameState.class, r), null, "X", "Y");
        assertProceeded(r, handler);

        r = new Recorder();
        handler.subscribe(proxy(SubscribeState.class, r), null, "X");
        assertProceeded(r, handler);

        r = new Recorder();
        handler.unsubscribe(proxy(SubscribeState.class, r), null, "X");
        assertProceeded(r, handler);

        r = new Recorder();
        handler.list(proxy(ListState.class, r), null, "", "*");
        assertProceeded(r, handler);

        r = new Recorder();
        handler.lsub(proxy(ListState.class, r), null, "", "*");
        assertProceeded(r, handler);

        r = new Recorder();
        Set<StatusItem> items = new java.util.HashSet<StatusItem>();
        handler.status(proxy(AuthenticatedStatusState.class, r), null, "INBOX", items);
        assertProceeded(r, handler);

        r = new Recorder();
        handler.status(proxy(SelectedStatusState.class, r), null, "INBOX", items);
        assertProceeded(r, handler);

        r = new Recorder();
        handler.append(proxy(AppendState.class, r), null, "INBOX",
                EnumSet.noneOf(Flag.class), null);
        assertProceeded(r, handler);
    }

    @Test
    public void testQuotaCallbacks() {
        Map<String, Long> limits = new HashMap<String, Long>();
        RoleBasedQuotaManager qm = new RoleBasedQuotaManager();

        Recorder r = new Recorder();
        handler.getQuotaRoot(proxy(QuotaState.class, r), qm, null, "INBOX");
        assertProceeded(r, handler);
        r = new Recorder();
        handler.getQuotaRoot(proxy(QuotaState.class, r), null, null, "INBOX");
        assertEquals(Collections.singletonList("quotaNotSupported"), r.calls);

        r = new Recorder();
        handler.setQuota(proxy(QuotaState.class, r), qm, null, "", limits);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.setQuota(proxy(QuotaState.class, r), null, null, "", limits);
        assertEquals(Collections.singletonList("quotaNotSupported"), r.calls);
    }

    @Test
    public void testSelectedCallbacks() {
        MessageSet set = null;
        Set<Flag> flags = EnumSet.of(Flag.SEEN);
        Set<String> fetchItems = new java.util.HashSet<String>();

        Recorder r = new Recorder();
        handler.close(proxy(CloseState.class, r), null);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.unselect(proxy(CloseState.class, r), null);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.expunge(proxy(ExpungeState.class, r), null);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.uidExpunge(proxy(ExpungeState.class, r), null, set);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.store(proxy(StoreState.class, r), null, set, StoreAction.ADD, flags, false);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.uidStore(proxy(StoreState.class, r), null, set, StoreAction.REMOVE, flags, true);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.copy(proxy(CopyState.class, r), null, null, set, "T");
        assertProceeded(r, handler);
        r = new Recorder();
        handler.uidCopy(proxy(CopyState.class, r), null, null, set, "T");
        assertProceeded(r, handler);
        r = new Recorder();
        handler.move(proxy(MoveState.class, r), null, null, set, "T");
        assertProceeded(r, handler);
        r = new Recorder();
        handler.uidMove(proxy(MoveState.class, r), null, null, set, "T");
        assertProceeded(r, handler);
        r = new Recorder();
        handler.fetch(proxy(FetchState.class, r), null, set, fetchItems);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.uidFetch(proxy(FetchState.class, r), null, set, fetchItems);
        assertProceeded(r, handler);
        SearchCriteria criteria = SearchCriteria.all();
        r = new Recorder();
        handler.search(proxy(SearchState.class, r), null, criteria);
        assertProceeded(r, handler);
        r = new Recorder();
        handler.uidSearch(proxy(SearchState.class, r), null, criteria);
        assertProceeded(r, handler);
    }
}
