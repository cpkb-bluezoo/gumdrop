/*
 * ListenerFluentSettersTest.java
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

package org.bluezoo.gumdrop;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.CidrNetwork;

import static org.junit.Assert.*;

/**
 * Every public one-argument configuration method that a concrete listener
 * declares and that returns the listener's own type must return the same
 * instance, so that configuration can be chained. The covariant overrides
 * of the base class setters are the bulk of what this exercises.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class ListenerFluentSettersTest {

    private static final String[] LISTENERS = {
        "org.bluezoo.gumdrop.ftp.FtpListener",
        "org.bluezoo.gumdrop.imap.ImapListener",
        "org.bluezoo.gumdrop.mdns.MdnsListener",
        "org.bluezoo.gumdrop.http.server.Http2Listener",
        "org.bluezoo.gumdrop.http.server.HttpListener",
        "org.bluezoo.gumdrop.http.h3.Http3Listener",
        "org.bluezoo.gumdrop.smtp.SmtpListener",
        "org.bluezoo.gumdrop.pop3.Pop3Listener",
        "org.bluezoo.gumdrop.nntp.NntpListener",
        "org.bluezoo.gumdrop.socks.SocksListener",
        "org.bluezoo.gumdrop.mqtt.MqttListener",
        "org.bluezoo.gumdrop.dns.DnsListener",
        "org.bluezoo.gumdrop.dns.DnsTcpListener",
        "org.bluezoo.gumdrop.dns.DoTListener",
        "org.bluezoo.gumdrop.dns.DoQListener"
    };

    /** A value for a parameter of this type, or null if the type is not one this test can supply. */
    private static Object argumentFor(Method method, Class<?> type) {
        if (type == int.class) {
            return Integer.valueOf(1);
        }
        if (type == long.class) {
            return Long.valueOf(1000L);
        }
        if (type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == String.class) {
            String name = method.getName();
            if (name.equals("rateLimit")) {
                return "5/10s";
            }
            return "x";
        }
        if (type == List.class) {
            return Collections.<CidrNetwork>emptyList();
        }
        if (type == InetAddress[].class) {
            return new InetAddress[0];
        }
        if (type == TlsConfig.class) {
            return TlsConfig.pem(Paths.get("c.pem"), Paths.get("k.pem"));
        }
        return null;
    }

    @Test
    public void fluentSettersReturnTheListener() throws Exception {
        int invoked = 0;
        List<String> failures = new ArrayList<String>();
        for (int i = 0; i < LISTENERS.length; i++) {
            Class<?> type = Class.forName(LISTENERS[i]);
            Method[] methods = type.getDeclaredMethods();
            for (int j = 0; j < methods.length; j++) {
                Method method = methods[j];
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.isBridge() || method.isSynthetic()
                        || method.getReturnType() != type || method.getParameterCount() != 1) {
                    continue;
                }
                Object argument = argumentFor(method, method.getParameterTypes()[0]);
                if (argument == null) {
                    continue;
                }
                Object listener = type.getDeclaredConstructor().newInstance();
                try {
                    Object result = method.invoke(listener, argument);
                    if (result != listener) {
                        failures.add(type.getSimpleName() + "." + method.getName() + " returned a different object");
                    }
                    invoked++;
                } catch (InvocationTargetException e) {
                    failures.add(type.getSimpleName() + "." + method.getName() + " threw " + e.getCause());
                }
            }
        }
        assertTrue(failures.toString(), failures.isEmpty());
        assertTrue("expected to exercise many setters, got " + invoked, invoked > 100);
    }
}
