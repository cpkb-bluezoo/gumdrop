/*
 * StubDnsTable.java
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

package org.bluezoo.gumdrop.smtp.auth;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsQueryCallback;
import org.bluezoo.gumdrop.dns.DnsQuestion;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsResolver;

/**
 * Deterministic in-memory resolver for the email authentication tests:
 * answers come from a table keyed by record type and lower-cased name,
 * unknown names are NXDOMAIN, selected names can be made to fail with a
 * transport error or to answer with a given response code, and every query
 * is logged as {@code TYPE:name} so tests can assert what was asked.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class StubDnsTable extends DnsResolver {

    private final Map<String, List<DnsResourceRecord>> table =
            new HashMap<String, List<DnsResourceRecord>>();
    private final Set<String> failing = new HashSet<String>();
    private final Map<String, Integer> rcodes = new HashMap<String, Integer>();
    private final List<String> queries = new ArrayList<String>();

    StubDnsTable() {
        super();
    }

    /** Queries made so far, as TYPE:name in order. */
    List<String> queries() {
        return queries;
    }

    private static String key(DnsType type, String name) {
        return type + ":" + name.toLowerCase();
    }

    void add(DnsType type, String name, DnsResourceRecord rr) {
        String k = key(type, name);
        List<DnsResourceRecord> list = table.get(k);
        if (list == null) {
            list = new ArrayList<DnsResourceRecord>();
            table.put(k, list);
        }
        list.add(rr);
    }

    void txt(String name, String text) {
        add(DnsType.TXT, name, DnsResourceRecord.txt(name, 300, text));
    }

    /** Replaces any TXT records of the name with this single one. */
    void replaceTxt(String name, String text) {
        table.remove(key(DnsType.TXT, name));
        txt(name, text);
    }

    void a(String name, String ip) throws Exception {
        add(DnsType.A, name, DnsResourceRecord.a(name, 300, InetAddress.getByName(ip)));
    }

    void aaaa(String name, String ip) throws Exception {
        add(DnsType.AAAA, name, DnsResourceRecord.aaaa(name, 300, InetAddress.getByName(ip)));
    }

    void mx(String name, int preference, String exchange) {
        add(DnsType.MX, name, DnsResourceRecord.mx(name, 300, preference, exchange));
    }

    void ptr(String name, String target) {
        add(DnsType.PTR, name, DnsResourceRecord.ptr(name, 300, target));
    }

    /** Makes queries of this type and name fail with a transport error. */
    void fail(DnsType type, String name) {
        failing.add(key(type, name));
    }

    /** Makes queries of this type and name answer empty with this rcode. */
    void rcode(DnsType type, String name, int rcode) {
        rcodes.put(key(type, name), Integer.valueOf(rcode));
    }

    private void answer(DnsType type, String name, DnsQueryCallback callback) {
        queries.add(key(type, name));
        String k = key(type, name);
        if (failing.contains(k)) {
            callback.onError("boom");
            return;
        }
        List<DnsResourceRecord> records = table.get(k);
        int rcode = DnsMessage.RCODE_NOERROR;
        Integer forced = rcodes.get(k);
        if (forced != null) {
            rcode = forced.intValue();
            records = null;
        }
        if (records == null) {
            records = Collections.emptyList();
            if (forced == null) {
                rcode = DnsMessage.RCODE_NXDOMAIN;
            }
        }
        int flags = DnsMessage.FLAG_QR | DnsMessage.FLAG_RA | rcode;
        callback.onResponse(new DnsMessage(1, flags,
                Collections.<DnsQuestion>emptyList(), records,
                Collections.<DnsResourceRecord>emptyList(),
                Collections.<DnsResourceRecord>emptyList()));
    }

    @Override
    public void queryTXT(String name, DnsQueryCallback callback) {
        answer(DnsType.TXT, name, callback);
    }

    @Override
    public void queryA(String name, DnsQueryCallback callback) {
        answer(DnsType.A, name, callback);
    }

    @Override
    public void queryAAAA(String name, DnsQueryCallback callback) {
        answer(DnsType.AAAA, name, callback);
    }

    @Override
    public void queryMX(String name, DnsQueryCallback callback) {
        answer(DnsType.MX, name, callback);
    }

    @Override
    public void queryPTR(String name, DnsQueryCallback callback) {
        answer(DnsType.PTR, name, callback);
    }

    @Override
    public void query(String name, DnsType type, DnsQueryCallback callback) {
        answer(type, name, callback);
    }
}
