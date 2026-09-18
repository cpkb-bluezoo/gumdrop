/*
 * ServerXmlLoader.java
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

package org.bluezoo.gumdrop.servlet.container;

import org.bluezoo.gonzalez.Parser;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import org.bluezoo.gumdrop.auth.BasicRealm;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.Context;
import org.bluezoo.gumdrop.servlet.server.ServletRequestHandler;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.XMLParseUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.StandardOpenOption;

/**
 * Reads the servlet container's own minimal {@code server.xml} — realm,
 * session-cluster settings, webapp contexts, and HTTP listeners — and
 * composes an {@link HttpServer} from it.
 *
 * <p>Parsing is entirely non-blocking: the file is read with an
 * {@link AsynchronousFileChannel} and fed chunk by chunk into the Gonzalez
 * push parser ({@link Parser#receive(ByteBuffer)}), the same pattern used
 * by {@code org.bluezoo.gumdrop.webdav.DeadPropertyStore} for property
 * sidecar files. No thread blocks on file I/O or on the parse itself.
 *
 * <p>This is deliberately narrow: it is not a general property-reflection
 * config system like Gumdrop 2.x's {@code gumdroprc} (removed in workstream
 * C.5, see {@code docs/GUMDROP-3-PLAN.md}). It exists only so the stock
 * servlet container distribution ({@code bin/gumdrop.sh} via {@link
 * org.bluezoo.gumdrop.Bootstrap}) can deploy webapps without a hand-written
 * {@code main()} — every other protocol, and any more elaborate servlet
 * deployment, still composes directly in Java (see {@code
 * web/configuration.html}).
 *
 * <h2>Format</h2>
 * <pre>{@code
 * <?xml version='1.0' standalone='yes'?>
 * <server>
 *   <realm name="myRealm,Gumdrop Manager" class="org.bluezoo.gumdrop.auth.BasicRealm"
 *          href="realm-servlet.xml"/>
 *
 *   <cluster port="4001" group-address="228.0.0.4" key="a1b2c3d4..."/>
 *
 *   <context path="" root="../webapps/ROOT" distributable="true"/>
 *   <context path="/manager" root="../webapps/manager.war"/>
 *
 *   <listener port="8080"/>
 *   <listener port="8443" secure="true" keystore-file="keystore.p12"
 *             keystore-pass="changeit" bind-wildcard="true"/>
 * </server>
 * }</pre>
 *
 * <p>{@code realm}'s {@code name} may be a comma-separated list of aliases
 * for the same realm instance, since a webapp's {@code web.xml
 * <realm-name>} is a per-application label (and may itself contain spaces)
 * -- the stock manager webapp expects to authenticate against a realm named
 * {@code "Gumdrop Manager"}.
 *
 * <p>Relative {@code href}, {@code root}, and {@code keystore-file} paths
 * resolve against the directory containing {@code server.xml} (typically
 * {@code conf/}), not the process's working directory.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ContainerMain
 */
public final class ServerXmlLoader {

    private static final int BUFFER_SIZE = 8192;

    private ServerXmlLoader() {
    }

    /**
     * Receives the outcome of an asynchronous {@link #load} call.
     */
    public interface Callback {

        /**
         * The container described by {@code server.xml} was composed
         * successfully.
         *
         * @param server the composed, not-yet-started server
         */
        void onServer(HttpServer server);

        /**
         * {@code server.xml} could not be read or was invalid.
         *
         * @param error a description of the failure
         */
        void onError(String error);
    }

    /**
     * Asynchronously parses {@code configFile} and composes the servlet
     * container it describes.
     *
     * @param configFile the {@code server.xml} file
     * @param callback receives the composed server, or an error
     */
    public static void load(File configFile, Callback callback) {
        File baseDir = configFile.getAbsoluteFile().getParentFile();
        AsynchronousFileChannel channel;
        try {
            channel = AsynchronousFileChannel.open(configFile.toPath(), StandardOpenOption.READ);
        } catch (IOException e) {
            callback.onError("Cannot open " + configFile + ": " + e.getMessage());
            return;
        }
        Handler handler = new Handler(baseDir);
        Parser parser = new Parser();
        parser.setContentHandler(handler);
        parser.setEntityResolver(XMLParseUtils.DENY_EXTERNAL_ENTITIES);
        parser.setSystemId(configFile.toURI().toString());
        new AsyncReader(channel, parser, handler, callback).start();
    }

    /**
     * Drives the positional {@link AsynchronousFileChannel} reads that feed
     * the push parser, carrying forward any bytes the parser could not yet
     * consume (e.g. a token split across a chunk boundary) exactly as
     * {@link XMLParseUtils}'s blocking channel loop does, just via
     * {@link CompletionHandler} instead of a blocking {@code while} loop.
     */
    private static final class AsyncReader {

        private final AsynchronousFileChannel channel;
        private final Parser parser;
        private final Handler handler;
        private final Callback callback;
        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        private long filePosition;

        AsyncReader(AsynchronousFileChannel channel, Parser parser, Handler handler,
                Callback callback) {
            this.channel = channel;
            this.parser = parser;
            this.handler = handler;
            this.callback = callback;
        }

        void start() {
            readNext();
        }

        private void readNext() {
            channel.read(buffer, filePosition, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer result, Void attachment) {
                    if (result == null || result < 0) {
                        finish();
                        return;
                    }
                    filePosition += result;
                    buffer.flip();
                    try {
                        parser.receive(buffer);
                    } catch (SAXException e) {
                        fail("server.xml parse error: " + e.getMessage());
                        return;
                    }
                    buffer.compact();
                    readNext();
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    fail("Error reading server.xml: " + exc.getMessage());
                }
            });
        }

        private void finish() {
            closeQuietly(channel);
            try {
                parser.close();
            } catch (SAXException e) {
                callback.onError("server.xml parse error: " + e.getMessage());
                return;
            }
            try {
                callback.onServer(handler.build());
            } catch (SAXException e) {
                callback.onError(e.getMessage());
            }
        }

        private void fail(String message) {
            closeQuietly(channel);
            callback.onError(message);
        }
    }

    private static void closeQuietly(AsynchronousFileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        }
    }

    private static final class Handler extends DefaultHandler {

        private final File baseDir;
        private final Container container = new Container();
        private final HttpServer.Composer composer = HttpServer.compose();
        private boolean haveListener;

        Handler(File baseDir) {
            this.baseDir = baseDir;
        }

        private File resolve(String path) {
            File f = new File(path);
            return f.isAbsolute() ? f : new File(baseDir, path);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs)
                throws SAXException {
            switch (qName) {
                case "realm":
                    startRealm(attrs);
                    break;
                case "cluster":
                    startCluster(attrs);
                    break;
                case "context":
                    startContext(attrs);
                    break;
                case "listener":
                    startListener(attrs);
                    break;
                case "server":
                    break;
                default:
                    throw new SAXException("Unrecognised server.xml element: " + qName);
            }
        }

        private void startRealm(Attributes attrs) throws SAXException {
            String name = require(attrs, "name", "realm");
            String className = require(attrs, "class", "realm");
            Realm realm;
            try {
                realm = (Realm) Class.forName(className)
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new SAXException("Cannot instantiate realm class " + className, e);
            }
            String href = attrs.getValue("href");
            if (href != null) {
                if (!(realm instanceof BasicRealm)) {
                    throw new SAXException(
                            "realm href is only supported for BasicRealm, not " + className);
                }
                ((BasicRealm) realm).setHref(resolve(href).toPath());
            }
            // A webapp's web.xml <realm-name> is a per-application label, so
            // the same realm is commonly registered under more than one name
            // (e.g. the manager webapp expects "Gumdrop Manager", itself
            // containing a space); accept a comma-separated list rather
            // than requiring one <realm> element (and one parse of the
            // same href) per alias.
            for (String alias : name.split(",")) {
                alias = alias.trim();
                if (!alias.isEmpty()) {
                    container.addRealm(alias, realm);
                }
            }
        }

        private void startCluster(Attributes attrs) throws SAXException {
            String port = require(attrs, "port", "cluster");
            container.setClusterPort(Integer.parseInt(port));
            String groupAddress = attrs.getValue("group-address");
            if (groupAddress != null) {
                container.setClusterGroupAddress(groupAddress);
            }
            String key = require(attrs, "key", "cluster");
            container.setClusterKey(key);
        }

        private void startContext(Attributes attrs) throws SAXException {
            String path = attrs.getValue("path");
            if (path == null) {
                throw new SAXException("context requires a path attribute (\"\" for root)");
            }
            String root = require(attrs, "root", "context");
            Context context = new Context(container, path, resolve(root));
            String distributable = attrs.getValue("distributable");
            if (distributable != null) {
                context.setDistributable(Boolean.parseBoolean(distributable));
            }
            container.addContext(context);
        }

        private void startListener(Attributes attrs) throws SAXException {
            String portValue = require(attrs, "port", "listener");
            int port = Integer.parseInt(portValue);
            boolean secure = Boolean.parseBoolean(attrs.getValue("secure"));
            boolean bindWildcard = Boolean.parseBoolean(attrs.getValue("bind-wildcard"));

            Http2Listener http2 = new Http2Listener().port(port);
            if (bindWildcard) {
                http2.bindWildcard();
            }
            if (secure) {
                String keystoreFile = require(attrs, "keystore-file", "secure listener");
                String keystorePass = require(attrs, "keystore-pass", "secure listener");
                TlsConfig tls = TlsConfig.keystore(resolve(keystoreFile).toPath(), keystorePass);
                http2.secure(true).tls(tls);
                composer.listener(http2);

                Http3Listener http3 = new Http3Listener().port(port).tls(tls);
                if (bindWildcard) {
                    http3.bindWildcard();
                }
                composer.listener(http3);
            } else {
                composer.listener(http2);
            }
            haveListener = true;
        }

        private static String require(Attributes attrs, String name, String element)
                throws SAXException {
            String value = attrs.getValue(name);
            if (value == null) {
                throw new SAXException(element + " requires a " + name + " attribute");
            }
            return value;
        }

        HttpServer build() throws SAXException {
            if (!haveListener) {
                throw new SAXException("server.xml must configure at least one listener");
            }
            return composer.streamHandler(new ServletRequestHandler(container)).server();
        }
    }
}
