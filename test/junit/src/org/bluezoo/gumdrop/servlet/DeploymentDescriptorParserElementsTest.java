/*
 * DeploymentDescriptorParserElementsTest.java
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

import org.bluezoo.gumdrop.servlet.jndi.ServiceRef;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.xml.sax.SAXException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link DeploymentDescriptorParser} against the less common
 * deployment descriptor elements: JNDI references, resource definitions,
 * JSP configuration, security, session cookies and web fragments.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeploymentDescriptorParserElementsTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">\n";
    private static final String TAIL = "</web-app>";

    private WebFragment descriptor;
    private DeploymentDescriptorParser parser;

    @Before
    public void setUp() {
        descriptor = new WebFragment();
        parser = new DeploymentDescriptorParser();
    }

    private void parse(String body) throws IOException, SAXException {
        String xml = HEAD + body + TAIL;
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        try {
            parser.parse(descriptor, in);
        } finally {
            in.close();
        }
    }

    @Test
    public void testServletFullConfiguration() throws Exception {
        parse("<servlet><description>d</description><display-name>dn</display-name>"
                + "<servlet-name>s1</servlet-name><servlet-class>a.B</servlet-class>"
                + "<init-param><param-name>p</param-name><param-value>v</param-value></init-param>"
                + "<load-on-startup>bad</load-on-startup>"
                + "<async-supported>true</async-supported>"
                + "<run-as><role-name>admin</role-name></run-as>"
                + "<security-role-ref><role-name>r</role-name><role-link>l</role-link></security-role-ref>"
                + "<multipart-config><max-file-size>10</max-file-size>"
                + "<max-request-size>20</max-request-size>"
                + "<file-size-threshold>5</file-size-threshold></multipart-config>"
                + "</servlet>"
                + "<servlet><servlet-name>s2</servlet-name><jsp-file>/x.jsp</jsp-file>"
                + "<load-on-startup>2</load-on-startup></servlet>");
        assertEquals(2, descriptor.servletDefs.size());
        ServletDef s1 = descriptor.servletDefs.get("s1");
        assertNotNull(s1);
        assertEquals("a.B", s1.className);
        assertTrue(s1.asyncSupported);
        assertNotNull(s1.securityRoleRef);
        assertNotNull(s1.multipartConfig);
        ServletDef s2 = descriptor.servletDefs.get("s2");
        assertEquals("/x.jsp", s2.jspFile);
        assertEquals(2, s2.loadOnStartup);
    }

    @Test
    public void testFilterAsyncAndMappings() throws Exception {
        parse("<filter><description>d</description><display-name>dn</display-name>"
                + "<filter-name>f</filter-name><filter-class>a.F</filter-class>"
                + "<async-supported>true</async-supported>"
                + "<init-param><param-name>a</param-name><param-value>b</param-value></init-param></filter>"
                + "<filter-mapping><filter-name>f</filter-name><servlet-name>s</servlet-name>"
                + "<url-pattern>/x/*</url-pattern><dispatcher>FORWARD</dispatcher>"
                + "<dispatcher>ASYNC</dispatcher></filter-mapping>"
                + "<listener><description>d</description><display-name>x</display-name>"
                + "<listener-class>a.L</listener-class></listener>");
        FilterDef f = descriptor.filterDefs.get("f");
        assertNotNull(f);
        assertTrue(f.asyncSupported);
        assertEquals(1, descriptor.filterMappings.size());
        assertEquals(2, descriptor.filterMappings.get(0).dispatchers.size());
        assertEquals(1, descriptor.listenerDefs.size());
    }

    @Test
    public void testSessionConfigCookieAndTrackingModes() throws Exception {
        parse("<session-config><session-timeout>x</session-timeout>"
                + "<session-timeout>15</session-timeout>"
                + "<cookie-config><name>SID</name><domain>example.org</domain><path>/p</path>"
                + "<comment>c</comment><http-only>true</http-only><secure>true</secure>"
                + "<max-age>bad</max-age><max-age>60</max-age><same-site>Lax</same-site>"
                + "<partitioned>true</partitioned>"
                + "<attribute><name>X-A</name><value>1</value></attribute></cookie-config>"
                + "<tracking-mode>COOKIE</tracking-mode><tracking-mode>URL</tracking-mode>"
                + "</session-config>");
        assertNotNull(descriptor.sessionConfig);
        assertEquals(15, descriptor.sessionConfig.sessionTimeout);
        CookieConfig cc = descriptor.sessionConfig.cookieConfig;
        assertEquals("SID", cc.name);
        assertEquals("example.org", cc.domain);
        assertEquals("/p", cc.path);
        assertTrue(cc.httpOnly);
        assertTrue(cc.secure);
        assertEquals(60, cc.maxAge);
        assertTrue(cc.partitioned);
    }

    @Test
    public void testJspConfig() throws Exception {
        parse("<jsp-config><taglib><taglib-uri>u</taglib-uri><taglib-location>/l.tld</taglib-location></taglib>"
                + "<jsp-property-group><description>d</description><url-pattern>*.jsp</url-pattern>"
                + "<el-ignored>true</el-ignored><page-encoding>UTF-8</page-encoding>"
                + "<scripting-invalid>true</scripting-invalid>"
                + "<include-prelude>/p.jspf</include-prelude><include-coda>/c.jspf</include-coda>"
                + "<default-content-type>text/html</default-content-type>"
                + "<buffer>bad</buffer><buffer>16</buffer>"
                + "<trim-directive-whitespaces>true</trim-directive-whitespaces>"
                + "<is-xml>false</is-xml>"
                + "<deferred-syntax-allowed-as-literal>true</deferred-syntax-allowed-as-literal>"
                + "<error-on-undeclared-namespace>true</error-on-undeclared-namespace>"
                + "</jsp-property-group></jsp-config>");
        assertNotNull(descriptor.jspConfig);
        assertEquals(1, descriptor.jspConfig.getTaglibs().size());
        assertEquals(1, descriptor.jspConfig.getJspPropertyGroups().size());
    }

    @Test
    public void testSecurityConstraintsAndLogin() throws Exception {
        parse("<security-constraint><display-name>sc</display-name>"
                + "<web-resource-collection><web-resource-name>r</web-resource-name>"
                + "<description>d</description><url-pattern>/a/*</url-pattern>"
                + "<http-method>GET</http-method></web-resource-collection>"
                + "<auth-constraint><role-name>admin</role-name></auth-constraint>"
                + "<user-data-constraint><transport-guarantee>INTEGRAL</transport-guarantee>"
                + "</user-data-constraint></security-constraint>"
                + "<security-constraint><web-resource-collection><web-resource-name>o</web-resource-name>"
                + "<url-pattern>/b/*</url-pattern><http-method-omission>POST</http-method-omission>"
                + "</web-resource-collection><auth-constraint/>"
                + "<user-data-constraint><transport-guarantee>CONFIDENTIAL</transport-guarantee>"
                + "</user-data-constraint></security-constraint>"
                + "<login-config><auth-method>FORM</auth-method><realm-name>realm</realm-name>"
                + "<form-login-config><form-login-page>/login</form-login-page>"
                + "<form-error-page>/err</form-error-page></form-login-config></login-config>"
                + "<security-role><description>d</description><role-name>admin</role-name></security-role>");
        assertEquals(2, descriptor.securityConstraints.size());
        assertNotNull(descriptor.loginConfig);
        assertEquals("FORM", descriptor.loginConfig.authMethod);
        assertEquals("realm", descriptor.loginConfig.realmName);
        assertEquals("/login", descriptor.loginConfig.formLoginPage);
        assertEquals("/err", descriptor.loginConfig.formErrorPage);
        assertEquals(1, descriptor.securityRoles.size());
    }

    @Test
    public void testMiscTopLevelElements() throws Exception {
        parse("<mime-mapping><extension>xyz</extension><mime-type>text/x-xyz</mime-type></mime-mapping>"
                + "<error-page><error-code>x</error-code></error-page>"
                + "<error-page><error-code>404</error-code><location>/nf</location></error-page>"
                + "<error-page><exception-type>java.io.IOException</exception-type><location>/io</location></error-page>"
                + "<welcome-file-list><welcome-file>a.html</welcome-file></welcome-file-list>"
                + "<locale-encoding-mapping-list><locale-encoding-mapping><locale>fr</locale>"
                + "<encoding>ISO-8859-1</encoding></locale-encoding-mapping></locale-encoding-mapping-list>"
                + "<unknown-element>zzz</unknown-element>");
        assertEquals(1, descriptor.mimeMappings.size());
        assertEquals(3, descriptor.errorPages.size());
        assertEquals("ISO-8859-1", descriptor.localeEncodingMappings.get("fr"));
        assertEquals(1, descriptor.welcomeFiles.size());
    }

    @Test
    public void testJndiReferences() throws Exception {
        parse("<env-entry><description>d</description><env-entry-name>e1</env-entry-name>"
                + "<env-entry-type>java.lang.String</env-entry-type><env-entry-value>v</env-entry-value>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>f</injection-target-name></injection-target></env-entry>"
                + "<ejb-ref><description>d</description><ejb-ref-name>ejb/a</ejb-ref-name>"
                + "<ejb-ref-type>Session</ejb-ref-type><home>h</home><remote>r</remote>"
                + "<ejb-link>lnk</ejb-link><mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>g</injection-target-name></injection-target></ejb-ref>"
                + "<ejb-local-ref><description>d</description><ejb-ref-name>ejb/b</ejb-ref-name>"
                + "<ejb-ref-type>Entity</ejb-ref-type><local-home>lh</local-home><local>lc</local>"
                + "<ejb-link>lnk</ejb-link><mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>h</injection-target-name></injection-target></ejb-local-ref>"
                + "<resource-ref><description>d</description><res-ref-name>jdbc/x</res-ref-name>"
                + "<res-type>javax.sql.DataSource</res-type><res-auth>CONTAINER</res-auth>"
                + "<res-sharing-scope>Shareable</res-sharing-scope>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name></resource-ref>"
                + "<resource-env-ref><description>d</description>"
                + "<resource-env-ref-name>jms/q</resource-env-ref-name>"
                + "<resource-env-ref-type>jakarta.jms.Queue</resource-env-ref-type>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>q</injection-target-name></injection-target></resource-env-ref>"
                + "<message-destination-ref><description>d</description>"
                + "<message-destination-ref-name>jms/md</message-destination-ref-name>"
                + "<message-destination-type>jakarta.jms.Queue</message-destination-type>"
                + "<message-destination-usage>Consumes</message-destination-usage>"
                + "<message-destination-link>mdl</message-destination-link>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>md</injection-target-name></injection-target></message-destination-ref>"
                + "<message-destination><description>d</description><display-name>dn</display-name>"
                + "<message-destination-name>mdn</message-destination-name>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name></message-destination>"
                + "<persistence-context-ref><description>d</description>"
                + "<persistence-context-ref-name>pc</persistence-context-ref-name>"
                + "<persistence-unit-name>u</persistence-unit-name>"
                + "<persistence-context-type>Transaction</persistence-context-type>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>pc</injection-target-name></injection-target></persistence-context-ref>"
                + "<persistence-unit-ref><description>d</description>"
                + "<persistence-unit-ref-name>pu</persistence-unit-ref-name>"
                + "<persistence-unit-name>u</persistence-unit-name>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>pu</injection-target-name></injection-target></persistence-unit-ref>"
                + "<post-construct><lifecycle-callback-class>a.B</lifecycle-callback-class>"
                + "<lifecycle-callback-method>start</lifecycle-callback-method></post-construct>"
                + "<pre-destroy><lifecycle-callback-class>a.B</lifecycle-callback-class>"
                + "<lifecycle-callback-method>stop</lifecycle-callback-method></pre-destroy>");
        assertEquals(1, descriptor.envEntries.size());
        assertEquals(2, descriptor.ejbRefs.size());
        assertEquals(1, descriptor.resourceRefs.size());
        assertEquals(1, descriptor.resourceEnvRefs.size());
        assertEquals(1, descriptor.messageDestinationRefs.size());
        assertEquals(1, descriptor.messageDestinations.size());
        assertEquals(1, descriptor.persistenceContextRefs.size());
        assertEquals(1, descriptor.persistenceUnitRefs.size());
        assertEquals(1, descriptor.postConstructs.size());
        assertEquals(1, descriptor.preDestroys.size());
    }

    @Test
    public void testServiceRef() throws Exception {
        parse("<service-ref><description>d</description><display-name>dn</display-name>"
                + "<service-ref-name>svc/x</service-ref-name>"
                + "<service-interface>a.Svc</service-interface>"
                                + "<wsdl-file>x.wsdl</wsdl-file><jaxrpc-mapping-file>m.xml</jaxrpc-mapping-file>"
                + "<service-qname>q</service-qname><port-component-ref>pc</port-component-ref>"
                + "<handler-chains><handler-chain><protocol-bindings>##SOAP11_HTTP</protocol-bindings>"
                + "<port-name-pattern>p*</port-name-pattern>"
                + "<handler><handler-name>h2</handler-name><handler-class>a.H2</handler-class></handler>"
                + "</handler-chain></handler-chains>"
                + "<mapped-name>m</mapped-name><lookup-name>l</lookup-name>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>svc</injection-target-name></injection-target></service-ref>");
        assertEquals(1, descriptor.serviceRefs.size());
    }

    @Test
    public void testDataSource() throws Exception {
        parse("<data-source><description>d</description><name>jdbc/ds</name>"
                + "<class-name>a.Ds</class-name><server-name>localhost</server-name>"
                + "<port-number>bad</port-number><port-number>5432</port-number>"
                + "<database-name>db</database-name><user>u</user><password>p</password>"
                + "<isolation-level>TRANSACTION_SERIALIZABLE</isolation-level>"
                + "<initial-pool-size>1</initial-pool-size><initial-pool-size>x</initial-pool-size>"
                + "<max-pool-size>5</max-pool-size><max-pool-size>x</max-pool-size>"
                + "<min-pool-size>1</min-pool-size><min-pool-size>x</min-pool-size>"
                + "<max-idle-time>10</max-idle-time><max-idle-time>x</max-idle-time>"
                + "<max-statements>3</max-statements><max-statements>x</max-statements>"
                + "<transaction-isolation>TRANSACTION_READ_COMMITTED</transaction-isolation>"
                + "</data-source>");
        assertEquals(1, descriptor.dataSourceDefs.size());
        assertFalse(descriptor.dataSourceDefs.isEmpty());
    }

    @Test
    public void testJmsMailAndConnectors() throws Exception {
        parse("<jms-connection-factory><description>d</description><name>jms/cf</name>"
                + "<interface-name>jakarta.jms.ConnectionFactory</interface-name>"
                + "<class-name>a.Cf</class-name><user>u</user><password>p</password>"
                + "<client-id>c</client-id><transactional>true</transactional>"
                + "<resource-adapter>ra</resource-adapter>"
                + "<max-pool-size>5</max-pool-size><min-pool-size>1</min-pool-size>"
                + "</jms-connection-factory>"
                + "<jms-connection-factory><name>jms/cf2</name>"
                + "<pool><max-pool-size>5</max-pool-size><min-pool-size>1</min-pool-size>"
                + "<connection-timeout-in-seconds>30</connection-timeout-in-seconds></pool>"
                + "<pool><max-pool-size>x</max-pool-size><min-pool-size>x</min-pool-size>"
                + "<connection-timeout-in-seconds>x</connection-timeout-in-seconds></pool>"
                + "</jms-connection-factory>"
                + "<jms-destination><description>d</description><name>jms/q</name>"
                + "<interface-name>jakarta.jms.Queue</interface-name><class-name>a.Q</class-name>"
                + "</jms-destination>"
                + "<mail-session><description>d</description><name>mail/s</name>"
                + "<store-protocol>imap</store-protocol><store-protocol-class>a.I</store-protocol-class>"
                + "<transport-protocol>smtp</transport-protocol>"
                + "<transport-protocol-class>a.S</transport-protocol-class>"
                + "<host>h</host><user>u</user><password>p</password><from>f@x</from>"
                + "</mail-session>"
                + "<connection-factory><jndi-name>cf/x</jndi-name>"
                + "<connection-definition><connection-definition-id>id</connection-definition-id>"
                + "<config-property><config-property-name>n</config-property-name>"
                + "<config-property-value>v</config-property-value></config-property>"
                + "</connection-definition></connection-factory>"
                + "<administered-object><description>d</description><jndi-name>ao/x</jndi-name>"
                + "<administered-object-interface>a.I</administered-object-interface>"
                + "<administered-object-class>a.C</administered-object-class>"
                + "<lookup-name>l</lookup-name><mapped-name>m</mapped-name>"
                + "<config-property><config-property-name>n</config-property-name>"
                + "<config-property-value>v</config-property-value></config-property>"
                + "<injection-target><injection-target-class>a.B</injection-target-class>"
                + "<injection-target-name>ao</injection-target-name></injection-target>"
                + "</administered-object>");
        assertEquals(2, descriptor.jmsConnectionFactories.size());
        assertEquals(1, descriptor.jmsDestinations.size());
        assertEquals(1, descriptor.mailSessions.size());
        assertEquals(1, descriptor.connectionFactories.size());
        assertEquals(1, descriptor.administeredObjects.size());
    }

    @Test
    public void testFragmentOrdering() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-fragment xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.1\">"
                + "<name>frag1</name><ordering><before><name>a</name><others/></before>"
                + "<after><name>b</name><others/></after></ordering>"
                + "<display-name>fd</display-name></web-fragment>";
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        try {
            parser.parse(descriptor, in);
        } finally {
            in.close();
        }
        assertEquals("frag1", descriptor.name);
        assertEquals(2, descriptor.before.size());
        assertEquals(2, descriptor.after.size());
    }

    @Test
    public void testDigestComputed() throws Exception {
        parse("<display-name>x</display-name>");
        byte[] digest = parser.getDigest();
        assertNotNull(digest);
        assertEquals(16, digest.length);
    }

    @Test
    public void testMultipartConfigValuesStored() throws Exception {
        parse("<servlet><servlet-name>s</servlet-name><servlet-class>a.B</servlet-class>"
                + "<multipart-config><location>/tmp/up</location>"
                + "<max-file-size>10</max-file-size><max-request-size>20</max-request-size>"
                + "<file-size-threshold>5</file-size-threshold></multipart-config></servlet>");
        ServletDef s = descriptor.servletDefs.get("s");
        assertEquals("/tmp/up", s.multipartConfig.location);
        assertEquals(10L, s.multipartConfig.maxFileSize);
        assertEquals(20L, s.multipartConfig.maxRequestSize);
        assertEquals(5L, s.multipartConfig.fileSizeThreshold);
    }

    @Test
    public void testRunAsAndSecurityRoleRefStored() throws Exception {
        parse("<servlet><servlet-name>s</servlet-name><servlet-class>a.B</servlet-class>"
                + "<run-as><description>d</description><role-name>admin</role-name></run-as>"
                + "<security-role-ref><description>d2</description><role-name>r</role-name>"
                + "<role-link>l</role-link></security-role-ref></servlet>");
        ServletDef s = descriptor.servletDefs.get("s");
        assertEquals("admin", s.runAs);
        assertEquals("r", s.securityRoleRef.roleName);
        assertEquals("l", s.securityRoleRef.roleLink);
    }

    @Test
    public void testResourcePropertiesParsed() throws Exception {
        parse("<data-source><name>jdbc/ds</name><class-name>a.Ds</class-name>"
                + "<property><name>k</name><value>v</value></property></data-source>"
                + "<jms-connection-factory><name>jms/cf</name>"
                + "<property><name>k</name><value>v</value></property></jms-connection-factory>"
                + "<jms-destination><name>jms/q</name>"
                + "<property><name>k</name><value>v</value></property></jms-destination>"
                + "<mail-session><name>mail/s</name>"
                + "<property><name>mail.debug</name><value>true</value></property></mail-session>");
        assertEquals(1, descriptor.dataSourceDefs.size());
        assertEquals(1, descriptor.jmsConnectionFactories.size());
        assertEquals(1, descriptor.jmsDestinations.size());
        assertEquals(1, descriptor.mailSessions.size());
    }

    @Test
    public void testServiceRefHandlerAndType() throws Exception {
        parse("<service-ref><service-ref-name>svc/x</service-ref-name>"
                + "<service-ref-type>a.Svc</service-ref-type>"
                + "<handler><handler-name>h</handler-name><handler-class>a.H</handler-class></handler>"
                + "<mapped-name>m</mapped-name></service-ref>");
        assertEquals(1, descriptor.serviceRefs.size());
        ServiceRef ref = descriptor.serviceRefs.get(0);
        assertEquals("svc/x", ref.getName());
        assertEquals("m", ref.getMappedName());
    }
}
