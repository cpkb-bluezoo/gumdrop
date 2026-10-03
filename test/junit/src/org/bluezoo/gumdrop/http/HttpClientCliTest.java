/*
 * HttpClientCliTest.java
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

package org.bluezoo.gumdrop.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.bluezoo.gumdrop.testsupport.MessageEvents;
import org.junit.Test;

import org.bluezoo.gumdrop.http.client.HttpResponseHandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The command-line front end of {@link HttpClient}: argument parsing
 * and the response handler used by the CLI request runner.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class HttpClientCliTest {

    private static String[] args(String... a) {
        return a;
    }

    /** Parses and returns the diagnostics written; the options go to the holder. */
    private static String parseError(String[] a) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(bytes, true);
        HttpClient.CliOptions o = HttpClient.parseArguments(a, err);
        assertNull(o);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static HttpClient.CliOptions parse(String... a) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(bytes, true);
        HttpClient.CliOptions o = HttpClient.parseArguments(a, err);
        assertNotNull(new String(bytes.toByteArray(), StandardCharsets.UTF_8), o);
        return o;
    }

    @Test
    public void parsesEveryOption() {
        HttpClient.CliOptions o = parse("-X", "POST", "-H", "A: b", "-H", "C: d", "-d", "body.bin",
                "-o", "out.bin", "--http2", "-E", "c.pem:k.pem", "-k", "-v",
                "https://h.example:8443/p?q=1");
        assertEquals("POST", o.method);
        assertEquals(2, o.requestHeaders.size());
        assertEquals("body.bin", o.bodyFile);
        assertEquals("out.bin", o.outputFile);
        assertEquals("2", o.forceVersion);
        assertEquals("c.pem", o.pemCert);
        assertEquals("k.pem", o.pemKey);
        assertTrue(o.skipVerify);
        assertTrue(o.verbose);
        assertFalse(o.headersOnly);
        assertEquals("https", o.scheme);
        assertEquals("h.example", o.host);
        assertEquals(8443, o.port);
        assertEquals("/p?q=1", o.path);
    }

    @Test
    public void defaultsAndVersionFlags() {
        HttpClient.CliOptions o = parse("http://plain.example");
        assertEquals("GET", o.method);
        assertEquals("http", o.scheme);
        assertEquals(80, o.port);
        assertEquals("/", o.path);
        assertNull(o.forceVersion);
        assertEquals(443, parse("https://s.example/x").port);
        assertEquals("1.1", parse("--http1.1", "http://a").forceVersion);
        assertEquals("3", parse("--http3", "https://a").forceVersion);
        HttpClient.CliOptions head = parse("-I", "http://a/");
        assertTrue(head.headersOnly);
        assertEquals("HEAD", head.method);
    }

    @Test
    public void missingOptionArgumentsAreReported() {
        String[] flags = {"-X", "-H", "-d", "-o", "-E"};
        for (int i = 0; i < flags.length; i++) {
            String text = parseError(args(flags[i]));
            assertTrue(text, text.contains("Missing argument for " + flags[i]));
        }
    }

    @Test
    public void malformedInvocationsAreReported() {
        assertTrue(parseError(args("-E", "nocolon", "http://a")).contains("expected cert:key"));
        String unknown = parseError(args("-Z", "http://a"));
        assertTrue(unknown.contains("Unknown option: -Z"));
        assertTrue(unknown.contains("Usage: HttpClient"));
        assertTrue(parseError(args()).contains("Usage: HttpClient"));
        assertTrue(parseError(args("ftp://a")).contains("URL must start with"));
        assertTrue(parseError(args("http://a:port/")).contains("Invalid port number"));
    }

    private static final class Out {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final WritableByteChannel channel = Channels.newChannel(bytes);
    }

    private static String captureErr(Runnable action) {
        PrintStream saved = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(bytes, true));
        try {
            action.run();
        } finally {
            System.setErr(saved);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void quietHandlerWritesBodyAndSignalsCompletion() {
        final Out out = new Out();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        final HttpClient client = new HttpClient("example.test", 80);
        final HttpResponseHandler h = HttpClient.createResponseHandler(
                out.channel, false, false, false, done, error, client);
        String noise = captureErr(new Runnable() {
            @Override
            public void run() {
                h.status(HttpStatus.OK.code);
                h.status(HttpStatus.NOT_FOUND.code);
                h.header("X", MessageEvents.octets("y"));
                h.endHeaders();
                h.bodyContent(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
            }
        });
        assertEquals("", noise);
        assertEquals("hello", new String(out.bytes.toByteArray(), StandardCharsets.US_ASCII));
        h.endMessage();
        assertEquals(0L, done.getCount());
        assertNull(error.get());
    }

    @Test
    public void verboseHandlerPrintsStatusAndHeaders() {
        final Out out = new Out();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        final HttpClient client = new HttpClient("example.test", 80);
        final HttpResponseHandler h = HttpClient.createResponseHandler(
                out.channel, false, true, false, done, error, client);
        String noise = captureErr(new Runnable() {
            @Override
            public void run() {
                h.status(HttpStatus.OK.code);
                h.status(HttpStatus.NOT_FOUND.code);
                h.header("X-Test", MessageEvents.octets("yes"));
                h.endHeaders();
            }
        });
        assertTrue(noise, noise.contains("HTTP/? 200"));
        assertTrue(noise, noise.contains("HTTP/? 404"));
        assertTrue(noise, noise.contains("X-Test: yes"));
    }

    @Test
    public void headersOnlyHandlerDropsTheBody() {
        final Out out = new Out();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        final HttpClient client = new HttpClient("example.test", 80);
        final HttpResponseHandler h = HttpClient.createResponseHandler(
                out.channel, false, false, true, done, error, client);
        String noise = captureErr(new Runnable() {
            @Override
            public void run() {
                h.status(HttpStatus.OK.code);
                h.bodyContent(ByteBuffer.wrap(new byte[] {1, 2, 3}));
            }
        });
        assertTrue(noise.contains("200"));
        assertEquals(0, out.bytes.size());
    }

    @Test
    public void stdoutHandlerFlushesAndSurvivesWriteFailures() {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        final HttpClient client = new HttpClient("example.test", 80);
        final WritableByteChannel broken = new WritableByteChannel() {
            @Override
            public int write(ByteBuffer src) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() {
            }
        };
        final HttpResponseHandler h = HttpClient.createResponseHandler(
                broken, true, false, false, done, error, client);
        h.bodyContent(ByteBuffer.wrap(new byte[] {1}));
        IOException failure = new IOException("boom");
        h.failed(failure);
        assertSame(failure, error.get());
        assertEquals(0L, done.getCount());
    }
}
