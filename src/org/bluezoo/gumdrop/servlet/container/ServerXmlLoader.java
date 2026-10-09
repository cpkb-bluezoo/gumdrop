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
import org.xml.sax.SAXNotSupportedException;

import org.bluezoo.gumdrop.auth.BasicRealm;
import org.bluezoo.gumdrop.auth.Realm;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.quic.tls.PemCredentials;
import org.bluezoo.gumdrop.tls.KeystoreFormat;
import org.bluezoo.gumdrop.tls.TlsVersion;
import org.bluezoo.gumdrop.util.TlsUtils;
import org.bluezoo.gumdrop.http.h3.Http3Listener;
import org.bluezoo.gumdrop.http.server.Http2Listener;
import org.bluezoo.gumdrop.servlet.Container;
import org.bluezoo.gumdrop.servlet.Context;
import org.bluezoo.gumdrop.servlet.server.ServletRequestHandler;
import org.bluezoo.gumdrop.tls.TlsConfig;
import org.bluezoo.gumdrop.util.AbstractXMLHandler;
import org.bluezoo.gumdrop.util.XMLParseUtils;

import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.time.Duration;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.StandardOpenOption;

/**
 * Reads the servlet container's own minimal {@code server.xml} — realm,
 * container settings, session-cluster settings, webapp contexts, and HTTP
 * listeners — and composes an {@link HttpServer} from it.
 *
 * <p>Parsing is entirely non-blocking: the file is read with an
 * {@link AsynchronousFileChannel} and fed chunk by chunk into the Gonzalez
 * push parser ({@link Parser#receive(ByteBuffer)}), the same pattern used
 * by {@code org.bluezoo.gumdrop.webdav.DeadPropertyStore} for property
 * sidecar files. No thread blocks on file I/O or on the parse itself.
 *
 * <p>This is deliberately narrow: it is not a general property-reflection
 * config system like Gumdrop 2.x's {@code gumdroprc} (removed in Gumdrop 3; see
 * {@code docs/MIGRATING-TO-3.md}). It exists only so the stock
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
 *   <container hot-deploy="true" buffer-size="8192"
 *              worker-core-pool-size="8" worker-maximum-pool-size="64"
 *              worker-keep-alive="60"/>
 *
 *   <cluster port="4001" group-address="228.0.0.4" key="64-hex-characters"/>
 *
 *   <context path="" root="../webapps/ROOT" distributable="true"/>
 *   <context path="/manager" root="../webapps/manager.war"/>
 *
 *   <listener port="8080"/>
 *   <listener port="8443" secure="true" cert-file="tls/cert.pem"
 *             key-file="tls/key.pem" bind-wildcard="true"/>
 * </server>
 * }</pre>
 *
 * <p>{@code container} is optional and all of its attributes are optional:
 * {@code hot-deploy} turns automatic redeployment on or off (otherwise the
 * {@code GUMDROP_HOT_DEPLOY} environment variable decides, default off),
 * {@code buffer-size} is the I/O buffer size in bytes, {@code
 * worker-core-pool-size} and {@code worker-maximum-pool-size} bound the
 * servlet worker pool, and {@code worker-keep-alive} is the idle timeout of
 * excess workers in seconds.
 *
 * <p>A secure listener takes its TLS identity either from PEM files
 * ({@code cert-file} and {@code key-file}, the simplest form) or from a Java
 * keystore ({@code keystore-file} and {@code keystore-pass}, PKCS#12 unless
 * {@code keystore-format} says otherwise, for example {@code JKS}), but not
 * both.
 *
 * <p>A secure listener also accepts {@code tls-version} ({@code NEGOTIATE},
 * {@code TLS_1_2} or {@code TLS_1_3}, applied to the TCP listener; HTTP/3 is
 * always TLS 1.3), {@code cipher-suites} and {@code named-groups}
 * (colon-separated, applied to both the TCP and HTTP/3 listeners), and
 * {@code client-auth} ({@code none}, the default, or {@code required} for
 * mutual TLS). Under {@code client-auth="required"} the trust anchors for
 * client certificates are a PEM CA bundle ({@code ca-file}) or a Java
 * truststore ({@code truststore-file}, {@code truststore-pass} and an
 * optional {@code truststore-format}), not both; without either the JVM
 * default trust store is used. Those files are read when {@code server.xml}
 * is loaded, so a missing or unreadable one is reported immediately.
 *
 * <p>A secure listener with a keystore identity may also serve several
 * certificates by Server Name Indication: {@code sni-default-alias} names the
 * keystore alias used when no host matches, and nested
 * {@code <sni host="example.com" alias="example-cert"/>} elements map a host
 * name (exact, or {@code *.example.org}) to a keystore alias. SNI selects
 * keystore aliases, so it is not available with a single PEM identity.
 *
 * <p>{@code realm}'s {@code name} may be a comma-separated list of aliases
 * for the same realm instance, since a webapp's {@code web.xml
 * <realm-name>} is a per-application label (and may itself contain spaces)
 * -- the stock manager webapp expects to authenticate against a realm named
 * {@code "Gumdrop Manager"}.
 *
 * <p>Relative {@code href}, {@code root}, {@code cert-file}, {@code key-file},
 * {@code keystore-file}, {@code ca-file}, {@code truststore-file} and ECH paths
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
        loadFrom(new ChannelSource(channel), baseDir, configFile.toURI().toString(), callback);
    }

    /**
     * Positional, asynchronous chunk reader the loader pulls the document
     * from. Package-private so unit tests can substitute an in-memory mock
     * for the real {@link AsynchronousFileChannel}.
     */
    interface ChunkSource {

        /**
         * Reads into {@code dst} from {@code position} and completes
         * {@code handler} with the byte count, or -1 at end of input.
         */
        void read(ByteBuffer dst, long position, CompletionHandler<Integer, Void> handler);

        /**
         * Releases the underlying resource.
         */
        void close();
    }

    /** {@link ChunkSource} backed by a real asynchronous file channel. */
    private static final class ChannelSource implements ChunkSource {

        private final AsynchronousFileChannel channel;

        ChannelSource(AsynchronousFileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void read(ByteBuffer dst, long position, CompletionHandler<Integer, Void> handler) {
            channel.read(dst, position, null, handler);
        }

        @Override
        public void close() {
            closeQuietly(channel);
        }
    }

    static void loadFrom(ChunkSource source, File baseDir, String systemId, Callback callback) {
        Handler handler = new Handler(baseDir);
        Parser parser = new Parser();
        setHandler(parser, handler);
        parser.setEntityResolver(XMLParseUtils.DENY_EXTERNAL_ENTITIES);
        parser.setSystemId(systemId);
        new AsyncReader(source, parser, handler, callback).start();
    }

    /**
     * Drives the positional {@link AsynchronousFileChannel} reads that feed
     * the push parser, carrying forward any bytes the parser could not yet
     * consume (e.g. a token split across a chunk boundary) exactly as
     * {@link XMLParseUtils}'s blocking channel loop does, just via
     * {@link CompletionHandler} instead of a blocking {@code while} loop.
     */
    private static final class AsyncReader {

        private final ChunkSource channel;
        private final Parser parser;
        private final Handler handler;
        private final Callback callback;
        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        private long filePosition;

        AsyncReader(ChunkSource channel, Parser parser, Handler handler,
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
            channel.read(buffer, filePosition, new CompletionHandler<Integer, Void>() {
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
            channel.close();
            complete(parser, handler, callback);
        }

        private void fail(String message) {
            channel.close();
            callback.onError(message);
        }
    }

    /**
     * Test-only entry point: parses {@code xml} held in memory instead of
     * reading a file, so unit tests can exercise the configuration
     * handling without disk I/O. Not for production use.
     *
     * @param baseDir directory that relative paths in the document resolve against
     * @param xml the complete server.xml content
     * @param callback receives the built server or the error
     */
    static void loadFromMemoryForTesting(File baseDir, ByteBuffer xml,
            Callback callback) {
        Handler handler = new Handler(baseDir);
        Parser parser = new Parser();
        setHandler(parser, handler);
        parser.setEntityResolver(XMLParseUtils.DENY_EXTERNAL_ENTITIES);
        try {
            parser.receive(xml);
        } catch (SAXException e) {
            callback.onError("server.xml parse error: " + e.getMessage());
            return;
        }
        complete(parser, handler, callback);
    }

    private static void complete(Parser parser, Handler handler, Callback callback) {
        try {
            parser.close();
        } catch (SAXException e) {
            callback.onError("server.xml parse error: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            // e.g. a zero-length file: the parser has no document to close.
            // Without this the callback would never fire on the reader thread.
            callback.onError("server.xml parse error: " + e);
            return;
        }
        try {
            callback.onServer(handler.build());
        } catch (SAXException e) {
            callback.onError(e.getMessage());
        }
    }

    /**
     * Wires {@code handler} onto a freshly-constructed {@code parser} as
     * its native {@code XMLHandler}. {@link Parser#setXMLHandler} only
     * declares {@link SAXNotSupportedException} for the case where a
     * document is already being parsed with a different handler -- never
     * true immediately after {@code new Parser()} -- so this turns that
     * impossible case into an {@link IllegalStateException} rather than
     * pushing a checked exception onto both call sites above.
     */
    private static void setHandler(Parser parser, Handler handler) {
        try {
            parser.setXMLHandler(handler);
        } catch (SAXNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(AsynchronousFileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        }
    }

    private static final class Handler extends AbstractXMLHandler {

        private final File baseDir;
        private final Container container = new Container();
        private final HttpServer.Composer composer = HttpServer.compose();
        private boolean haveListener;
        // the listener element being read, composed when it ends so that
        // its sni children can be applied first
        private boolean listenerOpen;
        private int openPort;
        private boolean openWildcard;
        private TlsConfig openTls;

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
                case "container":
                    startContainer(attrs);
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
                case "sni":
                    startSni(attrs);
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
                ((BasicRealm) realm).href(resolve(href).toPath());
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

        private void startContainer(Attributes attrs) throws SAXException {
            String hotDeploy = attrs.getValue("hot-deploy");
            if (hotDeploy != null) {
                container.hotDeploy(Boolean.parseBoolean(hotDeploy.trim()));
            }
            String bufferSize = attrs.getValue("buffer-size");
            if (bufferSize != null) {
                container.bufferSize(parseInt(bufferSize, "container buffer-size"));
            }
            String core = attrs.getValue("worker-core-pool-size");
            if (core != null) {
                container.workerCorePoolSize(parseInt(core, "container worker-core-pool-size"));
            }
            String max = attrs.getValue("worker-maximum-pool-size");
            if (max != null) {
                container.workerMaximumPoolSize(parseInt(max, "container worker-maximum-pool-size"));
            }
            String keepAlive = attrs.getValue("worker-keep-alive");
            if (keepAlive != null) {
                container.workerKeepAlive(Duration.ofSeconds(
                        parseInt(keepAlive, "container worker-keep-alive")));
            }
        }

        private void startCluster(Attributes attrs) throws SAXException {
            String port = require(attrs, "port", "cluster");
            container.clusterPort(parsePort(port, "cluster port"));
            String groupAddress = attrs.getValue("group-address");
            if (groupAddress != null) {
                try {
                    // a literal only: never a name lookup
                    container.clusterGroupAddress(InetAddress.ofLiteral(groupAddress.trim()));
                } catch (IllegalArgumentException e) {
                    throw new SAXException("cluster group-address must be a multicast IP address literal: "
                            + groupAddress, e);
                }
            }
            String key = require(attrs, "key", "cluster");
            container.clusterKey(decodeClusterKey(key));
        }

        private byte[] decodeClusterKey(String key) throws SAXException {
            if (key.length() != 64) {
                throw new SAXException("cluster key must be exactly 64 hexadecimal characters");
            }
            byte[] bytes = new byte[32];
            for (int i = 0; i < bytes.length; i++) {
                int high = Character.digit(key.charAt(i * 2), 16);
                int low = Character.digit(key.charAt(i * 2 + 1), 16);
                if (high < 0 || low < 0 || key.charAt(i * 2) > 'f' || key.charAt(i * 2 + 1) > 'f') {
                    throw new SAXException("cluster key must be exactly 64 hexadecimal characters");
                }
                bytes[i] = (byte) ((high << 4) | low);
            }
            return bytes;
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
                context.distributable(Boolean.parseBoolean(distributable));
            }
            container.addContext(context);
        }

        private void startListener(Attributes attrs) throws SAXException {
            String portValue = require(attrs, "port", "listener");
            int port = parsePort(portValue, "listener port");
            boolean secure = Boolean.parseBoolean(attrs.getValue("secure"));
            boolean bindWildcard = Boolean.parseBoolean(attrs.getValue("bind-wildcard"));

            listenerOpen = true;
            openPort = port;
            openWildcard = bindWildcard;
            openTls = null;
            if (secure) {
                TlsConfig tls = identity(attrs);
                String echConfigList = attrs.getValue("ech-config-list-file");
                String echPrivateKey = attrs.getValue("ech-private-key-file");
                if (echConfigList != null) {
                    tls.echConfigListFile(resolve(echConfigList).toPath());
                }
                if (echPrivateKey != null) {
                    tls.echPrivateKeyFile(resolve(echPrivateKey).toPath());
                }
                if (Boolean.parseBoolean(attrs.getValue("ech-required"))) {
                    tls.echServerRequired(true);
                }
                boolean clientAuth = clientAuthRequired(attrs);
                if (clientAuth) {
                    X509TrustManager trust = clientTrust(attrs);
                    if (trust != null) {
                        tls.trustManager(trust);
                    }
                } else if (attrs.getValue("ca-file") != null
                        || attrs.getValue("truststore-file") != null
                        || attrs.getValue("truststore-pass") != null
                        || attrs.getValue("truststore-format") != null) {
                    throw new SAXException("ca-file and truststore-file only apply "
                            + "with client-auth=\"required\"");
                }
                tls.requireClientAuth(clientAuth).tlsVersion(tlsVersion(attrs));
                String cipherSuites = attrs.getValue("cipher-suites");
                if (cipherSuites != null) {
                    tls.cipherSuites(cipherSuites.trim());
                }
                String namedGroups = attrs.getValue("named-groups");
                if (namedGroups != null) {
                    tls.namedGroups(namedGroups.trim());
                }
                String defaultAlias = attrs.getValue("sni-default-alias");
                if (defaultAlias != null) {
                    if (tls.getKeystoreFile() == null) {
                        throw new SAXException("sni-default-alias requires a keystore identity "
                                + "(keystore-file), because SNI selects keystore aliases");
                    }
                    tls.sniDefaultAlias(defaultAlias.trim());
                }
                openTls = tls;
            }
            haveListener = true;
        }

        private void startSni(Attributes attrs) throws SAXException {
            if (!listenerOpen) {
                throw new SAXException("sni must be inside a listener");
            }
            if (openTls == null) {
                throw new SAXException("sni requires a secure listener");
            }
            if (openTls.getKeystoreFile() == null) {
                throw new SAXException("sni requires a keystore identity (keystore-file), "
                        + "because it selects keystore aliases; PEM files hold a single identity");
            }
            String host = require(attrs, "host", "sni").trim();
            String alias = require(attrs, "alias", "sni").trim();
            if (openTls.getSniHostnames().containsKey(host)) {
                throw new SAXException("sni host listed twice: " + host);
            }
            openTls.sni(host, alias);
        }

        @Override
        protected void endElement(String uri, String localName, String qName)
                throws SAXException {
            if (!"listener".equals(qName)) {
                return;
            }
            Http2Listener http2 = new Http2Listener().port(openPort);
            if (openWildcard) {
                http2.bindWildcard();
            }
            if (openTls != null) {
                http2.secure(true).tls(openTls);
                Http3Listener http3 = new Http3Listener().port(openPort).tls(openTls);
                if (openWildcard) {
                    http3.bindWildcard();
                }
                composer.listener(http2);
                composer.listener(http3);
            } else {
                composer.listener(http2);
            }
            listenerOpen = false;
            openTls = null;
        }

        private static boolean clientAuthRequired(Attributes attrs) throws SAXException {
            String value = attrs.getValue("client-auth");
            if (value == null || value.trim().equals("none")) {
                return false;
            }
            if (value.trim().equals("required")) {
                return true;
            }
            throw new SAXException("client-auth must be none or required: " + value);
        }

        private static TlsVersion tlsVersion(Attributes attrs) throws SAXException {
            String value = attrs.getValue("tls-version");
            if (value == null) {
                return TlsVersion.NEGOTIATE;
            }
            try {
                return TlsVersion.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new SAXException("tls-version must be NEGOTIATE, TLS_1_2 or TLS_1_3: "
                        + value, e);
            }
        }

        /**
         * The trust anchors for client certificates under mutual TLS: a PEM
         * CA bundle ({@code ca-file}) or a Java truststore
         * ({@code truststore-file} and {@code truststore-pass}), or
         * {@code null} for the JVM default trust store.
         */
        private X509TrustManager clientTrust(Attributes attrs) throws SAXException {
            String caFile = attrs.getValue("ca-file");
            String trustFile = attrs.getValue("truststore-file");
            if (caFile != null && trustFile != null) {
                throw new SAXException("client trust takes ca-file or truststore-file, not both");
            }
            try {
                if (caFile != null) {
                    return PemCredentials.loadTrustManager(resolve(caFile).toPath());
                }
                if (trustFile == null) {
                    return null;
                }
                String pass = require(attrs, "truststore-pass", "secure listener");
                String format = attrs.getValue("truststore-format");
                KeystoreFormat keystoreFormat = format == null
                        ? KeystoreFormat.PKCS12 : KeystoreFormat.parse(format);
                TrustManager[] managers = TlsUtils.loadTrustManagers(
                        resolve(trustFile).toPath(), pass, keystoreFormat);
                for (TrustManager manager : managers) {
                    if (manager instanceof X509TrustManager) {
                        return (X509TrustManager) manager;
                    }
                }
                throw new SAXException("truststore-file " + trustFile + " has no X.509 trust anchors");
            } catch (IllegalArgumentException e) {
                throw new SAXException("truststore-format must be PKCS12, JKS or JCEKS: "
                        + attrs.getValue("truststore-format"), e);
            } catch (IOException | GeneralSecurityException e) {
                throw new SAXException("Cannot load client trust from "
                        + (caFile != null ? "ca-file " + caFile : "truststore-file " + trustFile)
                        + ": " + e.getMessage(), e);
            }
        }

        /**
         * The TLS identity of a secure listener: PEM files
         * ({@code cert-file} and {@code key-file}), or a Java keystore
         * ({@code keystore-file} and {@code keystore-pass}).
         */
        private TlsConfig identity(Attributes attrs) throws SAXException {
            boolean pem = attrs.getValue("cert-file") != null
                    || attrs.getValue("key-file") != null;
            boolean keystore = attrs.getValue("keystore-file") != null
                    || attrs.getValue("keystore-pass") != null
                    || attrs.getValue("keystore-format") != null;
            if (pem && keystore) {
                throw new SAXException("secure listener takes keystore-file, "
                        + "keystore-pass and keystore-format, or cert-file and "
                        + "key-file, not both");
            }
            if (pem) {
                String certFile = require(attrs, "cert-file", "secure listener");
                String keyFile = require(attrs, "key-file", "secure listener");
                return TlsConfig.pem(resolve(certFile).toPath(), resolve(keyFile).toPath());
            }
            if (!keystore) {
                throw new SAXException("secure listener requires keystore-file and "
                        + "keystore-pass, or cert-file and key-file");
            }
            String keystoreFile = require(attrs, "keystore-file", "secure listener");
            String keystorePass = require(attrs, "keystore-pass", "secure listener");
            String format = attrs.getValue("keystore-format");
            if (format == null) {
                return TlsConfig.keystore(resolve(keystoreFile).toPath(), keystorePass);
            }
            KeystoreFormat keystoreFormat;
            try {
                keystoreFormat = KeystoreFormat.parse(format);
            } catch (IllegalArgumentException e) {
                throw new SAXException("keystore-format must be PKCS12, JKS or JCEKS: " + format, e);
            }
            return TlsConfig.keystore(resolve(keystoreFile).toPath(), keystorePass, keystoreFormat);
        }

        private static int parsePort(String value, String what) throws SAXException {
            return parseInt(value, what);
        }

        private static int parseInt(String value, String what) throws SAXException {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new SAXException(what + " must be a number: " + value, e);
            }
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
