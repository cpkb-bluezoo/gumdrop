/*
 * DmarcMessageHandlerDelegationTest
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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentID;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.MimeLocator;
import org.bluezoo.gumdrop.mime.MimeVersion;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.MessageHandler;
import org.bluezoo.gumdrop.mime.rfc5322.ObsoleteStructureType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Checks that {@link DmarcMessageHandler} forwards every parser event to its
 * delegate unchanged and in order, intercepts only the From header for the
 * callback, and tolerates a missing delegate or callback.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DmarcMessageHandlerDelegationTest {

    /** Delegate that records each invocation with its arguments. */
    private static final class Recorder implements InvocationHandler {
        final List<String> names = new ArrayList<String>();
        final List<Object[]> arguments = new ArrayList<Object[]>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            names.add(method.getName());
            arguments.add(args);
            return null;
        }
    }

    private static MessageHandler proxy(Recorder recorder) {
        return (MessageHandler) Proxy.newProxyInstance(
                MessageHandler.class.getClassLoader(),
                new Class<?>[] {MessageHandler.class}, recorder);
    }

    private static void drive(MessageHandler handler, Object[] sample) throws Exception {
        handler.setLocator((MimeLocator) sample[0]);
        handler.startEntity("b1");
        handler.contentType((ContentType) sample[1]);
        handler.contentDisposition((ContentDisposition) sample[2]);
        handler.contentTransferEncoding("base64");
        handler.contentID((ContentID) sample[3]);
        handler.contentDescription("desc");
        handler.mimeVersion((MimeVersion) sample[4]);
        handler.header("X-Test", "v");
        handler.unexpectedHeader("X-Bad", "w");
        handler.dateHeader("Date", (OffsetDateTime) sample[5]);
        List<ContentID> ids = Collections.emptyList();
        handler.messageIDHeader("Message-ID", ids);
        handler.obsoleteStructure(ObsoleteStructureType.OBSOLETE_HEADER_SYNTAX);
        handler.endHeaders();
        handler.bodyContent(ByteBuffer.wrap(new byte[] {1, 2}));
        handler.unexpectedContent(ByteBuffer.wrap(new byte[] {3}));
        handler.endEntity("b1");
    }

    @Test
    public void everyEventIsForwardedInOrder() throws Exception {
        Recorder recorder = new Recorder();
        DmarcMessageHandler handler = new DmarcMessageHandler(null, proxy(recorder));
        Object[] sample = new Object[] {null, null, null, null, null, null};
        drive(handler, sample);
        String[] expected = {"setLocator", "startEntity", "contentType",
            "contentDisposition", "contentTransferEncoding", "contentID",
            "contentDescription", "mimeVersion", "header", "unexpectedHeader",
            "dateHeader", "messageIDHeader", "obsoleteStructure", "endHeaders",
            "bodyContent", "unexpectedContent", "endEntity"};
        assertEquals(expected.length, recorder.names.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], recorder.names.get(i));
        }
        assertEquals("X-Test", recorder.arguments.get(8)[0]);
        assertEquals("w", recorder.arguments.get(9)[1]);
        assertEquals("b1", recorder.arguments.get(16)[0]);
    }

    @Test
    public void missingDelegateIgnoresEveryEvent() throws Exception {
        DmarcMessageHandler handler = new DmarcMessageHandler(null, null);
        drive(handler, new Object[] {null, null, null, null, null, null});
        List<EmailAddress> from = Collections.singletonList(
                new EmailAddress(null, "a", "example.com", true));
        handler.addressHeader("From", from);
        handler.addressHeader("From", from);
    }

    @Test
    public void addressHeadersAreForwardedAndOnlyFromIsReported() throws Exception {
        Recorder recorder = new Recorder();
        final List<String> reported = new ArrayList<String>();
        DmarcMessageHandler handler = new DmarcMessageHandler(
                new DmarcMessageHandler.FromDomainCallback() {
                    @Override
                    public void onFromDomain(String domain) {
                        reported.add(domain);
                    }
                }, proxy(recorder));
        List<EmailAddress> to = Collections.singletonList(
                new EmailAddress(null, "t", "dest.example", true));
        handler.addressHeader("To", to);
        handler.addressHeader("FROM", Collections.singletonList(
                new EmailAddress(null, "a", "Example.com", true)));
        assertEquals(1, reported.size());
        assertEquals("Example.com", reported.get(0));
        assertEquals(2, recorder.names.size());
        assertSame(to, recorder.arguments.get(0)[1]);
    }

    @Test
    public void fromWithNoAddressesReportsNothingButMarksSeen() throws Exception {
        final List<String> reported = new ArrayList<String>();
        DmarcMessageHandler handler = new DmarcMessageHandler(
                new DmarcMessageHandler.FromDomainCallback() {
                    @Override
                    public void onFromDomain(String domain) {
                        reported.add(domain);
                    }
                }, null);
        handler.addressHeader("From", Collections.<EmailAddress>emptyList());
        assertTrue(reported.isEmpty());
        handler.addressHeader("From", null);
        assertEquals(1, reported.size());
        assertEquals(null, reported.get(0));
    }
}
