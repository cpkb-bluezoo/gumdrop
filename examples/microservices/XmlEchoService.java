/*
 * XmlEchoService.java
 * A microservice that parses an XML request as it arrives and answers in XML.
 */

import java.io.IOException;
import java.nio.ByteBuffer;

import org.bluezoo.gonzalez.Parser;
import org.bluezoo.gonzalez.XMLWriter;
import org.bluezoo.gumdrop.Gumdrop;
import org.bluezoo.gumdrop.http.HttpMethod;
import org.bluezoo.gumdrop.http.HttpServer;
import org.bluezoo.gumdrop.http.server.DefaultHttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpRequestHandler;
import org.bluezoo.gumdrop.http.server.HttpResponse;
import org.bluezoo.gumdrop.http.server.HttpStreamHandler;
import org.bluezoo.gumdrop.mime.ContentType;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class XmlEchoService {

    /** Remembers the root element and the text of the request. */
    static final class EchoContentHandler extends DefaultHandler {
        private final StringBuilder text = new StringBuilder();
        private String rootElement;

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes atts) throws SAXException {
            if (rootElement == null) {
                rootElement = localName;
            }
            text.setLength(0);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        String getRootElement() {
            return rootElement;
        }

        String getText() {
            return text.toString().trim();
        }
    }

    /** Handles POST /echo: the body is parsed chunk by chunk as it arrives. */
    static final class XmlEchoHandler extends DefaultHttpRequestHandler {
        private final Parser parser = new Parser();
        private final EchoContentHandler contentHandler = new EchoContentHandler();
        private final HttpResponse response;
        private HttpMethod method;
        private String path;
        private ContentType contentType;
        private boolean accepted;

        XmlEchoHandler(HttpResponse response) {
            this.response = response;
        }

        @Override
        public void method(HttpMethod method) {
            this.method = method;
        }

        @Override
        public void target(ByteBuffer target) {
            // a read-only view that is only valid during this call
            path = UTF_8.decode(target).toString();
        }

        @Override
        public void contentType(ContentType contentType) {
            this.contentType = contentType;
        }

        @Override
        public void endHeaders() {
            if (!"/echo".equals(path)) {
                sendError(404, "Not Found");
                return;
            }
            if (method != HttpMethod.POST) {
                sendError(405, "Method Not Allowed");
                return;
            }
            if (contentType == null || !contentType.isMimeType("application/xml")) {
                sendError(415, "Unsupported Media Type");
                return;
            }
            parser.setContentHandler(contentHandler);
            try {
                parser.reset();
                accepted = true;
            } catch (SAXException e) {
                sendError(500, e.getMessage());
            }
        }

        @Override
        public void bodyContent(ByteBuffer data) {
            if (!accepted) {
                return;
            }
            try {
                parser.receive(data);
            } catch (SAXException e) {
                accepted = false;
                sendError(400, "Invalid XML: " + e.getMessage());
            }
        }

        @Override
        public void endMessage() {
            if (!accepted) {
                return;
            }
            try {
                parser.close();
                sendXmlResponse();
            } catch (SAXException e) {
                sendError(400, "Invalid XML: " + e.getMessage());
            } catch (IOException e) {
                response.cancel();
            }
        }

        /** Writes the answer straight to the response as the XML is produced. */
        private void sendXmlResponse() throws IOException {
            String root = contentHandler.getRootElement();
            String text = contentHandler.getText();

            response.status(200);
            response.contentType(new ContentType("application", "xml", null));
            XMLWriter xml = new XMLWriter(new BodyChannel(response), 8192);
            xml.writeStartElement(root != null ? root : "response");
            xml.writeDefaultNamespace("urn:example:echo");
            if (!text.isEmpty()) {
                xml.writeCharacters(text);
            }
            xml.writeEndElement();
            xml.close();
            response.endMessage();
        }

        private void sendError(int code, String message) {
            response.status(code);
            response.contentType(new ContentType("text", "plain", null));
            response.bodyContent(ByteBuffer.wrap(message.getBytes(UTF_8)));
            response.endMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;

        HttpServer server = HttpServer.compose()
                .plaintextListener(port)
                .streamHandler(new HttpStreamHandler() {
                    @Override
                    public HttpRequestHandler openStream(HttpResponse response) {
                        return new XmlEchoHandler(response);
                    }
                })
                .server();

        Gumdrop gumdrop = Gumdrop.boot();
        gumdrop.addServer(server);
        System.out.println("XML echo service on port " + port);
        gumdrop.join();
    }

    private XmlEchoService() {
    }

}
