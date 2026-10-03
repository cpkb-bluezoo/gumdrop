/*
 * DeploymentDescriptorParserUnknownChildTest.java
 * Copyright (C) 2025 Chris Burdess
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


package org.bluezoo.gumdrop.servlet;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@link DeploymentDescriptorParser} tolerates unrecognised
 * child elements inside every container element of a deployment descriptor,
 * ignoring them without disturbing the recognised siblings, and that the
 * rarely used icon and multipart configuration elements are interpreted.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeploymentDescriptorParserUnknownChildTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">\n";
    private static final String TAIL = "</web-app>";
    private static final String Z = "<zzz>t</zzz>";

    private WebFragment parse(String body) throws IOException, SAXException {
        WebFragment descriptor = new WebFragment();
        DeploymentDescriptorParser parser = new DeploymentDescriptorParser();
        String xml = HEAD + body + TAIL;
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(bytes);
        try {
            parser.parse(descriptor, in);
        } finally {
            in.close();
        }
        return descriptor;
    }

    private static String wrap(String tag, String inner) {
        return "<" + tag + ">" + inner + Z + "</" + tag + ">";
    }

    @Test
    public void testServletContainersIgnoreUnknownChildren() throws Exception {
        String servlet = "<servlet><servlet-name>s</servlet-name>"
                + "<servlet-class>a.S</servlet-class>"
                + wrap("init-param", "<param-name>p</param-name><param-value>v</param-value>")
                + wrap("run-as", "<role-name>r</role-name>")
                + wrap("security-role-ref", "<role-name>x</role-name><role-link>y</role-link>")
                + wrap("multipart-config", "<location>/tmp</location>")
                + Z + "</servlet>";
        WebFragment d = parse(servlet
                + wrap("servlet-mapping", "<servlet-name>s</servlet-name><url-pattern>/s</url-pattern>")
                + wrap("context-param", "<param-name>c</param-name><param-value>1</param-value>")
                + Z);
        ServletDef def = d.servletDefs.get("s");
        assertNotNull(def);
        assertEquals("a.S", def.className);
        assertEquals(1, d.servletMappings.size());
        assertEquals("/tmp", def.multipartConfig.location);
    }

    @Test
    public void testFilterListenerAndSessionContainers() throws Exception {
        WebFragment d = parse(
                "<filter><filter-name>f</filter-name><filter-class>a.F</filter-class>"
                + wrap("init-param", "<param-name>p</param-name><param-value>v</param-value>")
                + Z + "</filter>"
                + wrap("filter-mapping", "<filter-name>f</filter-name><url-pattern>/*</url-pattern>")
                + wrap("listener", "<listener-class>a.L</listener-class>")
                + "<session-config><session-timeout>7</session-timeout>"
                + wrap("cookie-config", "<name>SID</name>"
                        + wrap("attribute", "<name>n</name><value>v</value>"))
                + Z + "</session-config>");
        assertNotNull(d.filterDefs.get("f"));
        assertEquals(1, d.filterMappings.size());
        assertEquals(1, d.listenerDefs.size());
        assertEquals(7, d.sessionConfig.sessionTimeout);
        assertEquals("SID", d.sessionConfig.cookieConfig.name);
    }

    @Test
    public void testMiscContainers() throws Exception {
        WebFragment d = parse(
                wrap("mime-mapping", "<extension>x</extension><mime-type>t/x</mime-type>")
                + wrap("welcome-file-list", "<welcome-file>i.html</welcome-file>")
                + wrap("error-page", "<error-code>404</error-code><location>/e</location>")
                + "<jsp-config>"
                + wrap("taglib", "<taglib-uri>u</taglib-uri><taglib-location>/l</taglib-location>")
                + wrap("jsp-property-group", "<url-pattern>*.jsp</url-pattern>")
                + Z + "</jsp-config>"
                + "<security-constraint><display-name>sc</display-name>"
                + wrap("web-resource-collection", "<web-resource-name>w</web-resource-name>"
                        + "<url-pattern>/a</url-pattern><http-method>GET</http-method>")
                + wrap("auth-constraint", "<role-name>admin</role-name>")
                + wrap("user-data-constraint", "<transport-guarantee>CONFIDENTIAL</transport-guarantee>")
                + Z + "</security-constraint>"
                + "<login-config><auth-method>FORM</auth-method><realm-name>r</realm-name>"
                + wrap("form-login-config", "<form-login-page>/l</form-login-page>"
                        + "<form-error-page>/e</form-error-page>")
                + Z + "</login-config>"
                + wrap("security-role", "<role-name>admin</role-name>")
                + wrap("locale-encoding-mapping-list",
                        wrap("locale-encoding-mapping", "<locale>en</locale><encoding>UTF-8</encoding>"))
                + wrap("post-construct", "<lifecycle-callback-class>a.B</lifecycle-callback-class>"
                        + "<lifecycle-callback-method>m</lifecycle-callback-method>")
                + wrap("pre-destroy", "<lifecycle-callback-class>a.B</lifecycle-callback-class>"
                        + "<lifecycle-callback-method>d</lifecycle-callback-method>"));
        assertEquals(1, d.mimeMappings.size());
        assertTrue(d.authentication);
        assertEquals(1, d.securityRoles.size());
    }

    @Test
    public void testJndiReferenceContainers() throws Exception {
        String inj = wrap("injection-target", "<injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>n</injection-target-name>");
        WebFragment d = parse(
                wrap("env-entry", "<env-entry-name>e</env-entry-name><env-entry-type>java.lang.String</env-entry-type>"
                        + "<env-entry-value>v</env-entry-value>" + inj)
                + wrap("ejb-ref", "<ejb-ref-name>ejb/a</ejb-ref-name><ejb-ref-type>Session</ejb-ref-type>"
                        + "<home>h</home><remote>r</remote>" + inj)
                + wrap("ejb-local-ref", "<ejb-ref-name>ejb/b</ejb-ref-name><local-home>h</local-home>"
                        + "<local>l</local>" + inj)
                + wrap("resource-ref", "<res-ref-name>jdbc/a</res-ref-name><res-type>t</res-type>"
                        + "<res-auth>CONTAINER</res-auth>" + inj)
                + wrap("resource-env-ref", "<resource-env-ref-name>r/a</resource-env-ref-name>"
                        + "<resource-env-ref-type>t</resource-env-ref-type>" + inj)
                + wrap("message-destination-ref", "<message-destination-ref-name>m/a</message-destination-ref-name>"
                        + "<message-destination-type>t</message-destination-type>" + inj)
                + wrap("persistence-context-ref", "<persistence-context-ref-name>p/a</persistence-context-ref-name>"
                        + "<persistence-unit-name>u</persistence-unit-name>" + inj)
                + wrap("persistence-unit-ref", "<persistence-unit-ref-name>p/b</persistence-unit-ref-name>"
                        + "<persistence-unit-name>u</persistence-unit-name>" + inj)
                + wrap("message-destination", "<message-destination-name>md</message-destination-name>"));
        assertNotNull(d);
        assertEquals(1, d.messageDestinations.size());
    }

    @Test
    public void testServiceRefHandlerContainers() throws Exception {
        String handler = wrap("handler", "<handler-name>h</handler-name><handler-class>a.H</handler-class>"
                + wrap("init-param", "<param-name>p</param-name><param-value>v</param-value>"));
        WebFragment d = parse(wrap("service-ref", "<service-ref-name>svc/a</service-ref-name>"
                + "<service-interface>i</service-interface>"
                + wrap("handler-chains", wrap("handler-chain",
                        "<protocol-bindings>##SOAP11_HTTP</protocol-bindings>" + handler))
                + handler
                + wrap("injection-target", "<injection-target-class>a.B</injection-target-class>")));
        assertNotNull(d);
    }

    @Test
    public void testDataSourceAndMessagingContainers() throws Exception {
        String prop = wrap("property", "<name>n</name><value>v</value>");
        WebFragment d = parse(
                wrap("data-source", "<name>java:comp/env/ds</name><class-name>a.D</class-name>"
                        + "<url>jdbc:x</url>" + prop)
                + wrap("jms-connection-factory", "<name>java:comp/env/cf</name>"
                        + wrap("pool", "<max-pool-size>5</max-pool-size><min-pool-size>1</min-pool-size>")
                        + prop)
                + wrap("jms-destination", "<name>java:comp/env/q</name>" + prop)
                + wrap("mail-session", "<name>java:comp/env/mail</name><host>h</host>" + prop)
                + wrap("connection-factory", "<jndi-name>java:comp/env/c</jndi-name>"
                        + wrap("connection-definition", "<id>1</id>"
                                + wrap("config-property", "<config-property-name>a</config-property-name>"
                                        + "<config-property-value>b</config-property-value>")))
                + wrap("administered-object", "<jndi-name>java:comp/env/o</jndi-name>"
                        + wrap("config-property", "<config-property-name>a</config-property-name>")));
        assertNotNull(d);
    }

    @Test
    public void testIconInsideServlet() throws Exception {
        WebFragment d = parse("<servlet><servlet-name>s</servlet-name><servlet-class>a.S</servlet-class>"
                + wrap("icon", "<small-icon>s.png</small-icon><large-icon>l.png</large-icon>")
                + "</servlet>");
        ServletDef def = d.servletDefs.get("s");
        assertEquals("s.png", def.getSmallIcon());
        assertEquals("l.png", def.getLargeIcon());
    }

    @Test
    public void testMultipartConfigBadNumbersAreIgnored() throws Exception {
        WebFragment d = parse("<servlet><servlet-name>s</servlet-name><servlet-class>a.S</servlet-class>"
                + "<multipart-config><max-file-size>x</max-file-size>"
                + "<max-request-size>y</max-request-size>"
                + "<file-size-threshold>z</file-size-threshold></multipart-config></servlet>");
        MultipartConfigDef mc = d.servletDefs.get("s").multipartConfig;
        assertNotNull(mc);
        assertEquals("", mc.location);
    }

    @Test
    public void testFragmentOrderingIgnoresUnknownChildren() throws Exception {
        WebFragment descriptor = new WebFragment();
        DeploymentDescriptorParser parser = new DeploymentDescriptorParser();
        String xml = "<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
                + "<name>f</name><ordering>"
                + wrap("before", "<name>a</name><others/>")
                + wrap("after", "<name>b</name><others/>")
                + Z + "</ordering></web-fragment>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(bytes);
        try {
            parser.parse(descriptor, in);
        } finally {
            in.close();
        }
        assertEquals(2, descriptor.before.size());
        assertEquals(2, descriptor.after.size());
    }
}
