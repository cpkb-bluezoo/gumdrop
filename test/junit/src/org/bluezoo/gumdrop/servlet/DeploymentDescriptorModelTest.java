/*
 * DeploymentDescriptorModelTest.java
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

import java.util.Collection;

import org.bluezoo.gumdrop.servlet.jndi.AdministeredObject;
import org.bluezoo.gumdrop.servlet.jndi.ConnectionFactory;
import org.bluezoo.gumdrop.servlet.jndi.DataSourceDef;
import org.bluezoo.gumdrop.servlet.jndi.EjbRef;
import org.bluezoo.gumdrop.servlet.jndi.EnvEntry;
import org.bluezoo.gumdrop.servlet.jndi.JmsConnectionFactory;
import org.bluezoo.gumdrop.servlet.jndi.Injectable;
import org.bluezoo.gumdrop.servlet.jndi.JmsDestination;
import org.bluezoo.gumdrop.servlet.jndi.MailSession;
import org.bluezoo.gumdrop.servlet.jndi.MessageDestinationRef;
import org.bluezoo.gumdrop.servlet.jndi.PersistenceContextRef;
import org.bluezoo.gumdrop.servlet.jndi.PersistenceUnitRef;
import org.bluezoo.gumdrop.servlet.jndi.Resource;
import org.bluezoo.gumdrop.servlet.jndi.ResourceEnvRef;
import org.bluezoo.gumdrop.servlet.jndi.ResourceRef;
import org.bluezoo.gumdrop.servlet.jndi.ServiceRef;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests the data-model behaviour of {@link DeploymentDescriptor}: emptiness,
 * reset, merging of fragments, resource collection and mapping resolution.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class DeploymentDescriptorModelTest {

    private static final int FIELD_COUNT = 36;

    /** Concrete descriptor with the base-class emptiness semantics. */
    private static final class Plain extends DeploymentDescriptor {
    }

    /** Populates exactly one descriptor member, chosen by index. */
    private static void populate(DeploymentDescriptor d, int i) {
        switch (i) {
        case 0:
            d.description = "d";
            break;
        case 1:
            d.displayName = "d";
            break;
        case 2:
            d.smallIcon = "s";
            break;
        case 3:
            d.largeIcon = "l";
            break;
        case 4:
            InitParam ip = new InitParam();
            ip.name = "n";
            ip.value = "v";
            d.addContextParam(ip);
            break;
        case 5:
            FilterDef fd = new FilterDef();
            fd.name = "f";
            d.addFilterDef(fd);
            break;
        case 6:
            d.addFilterMapping(new FilterMapping());
            break;
        case 7:
            d.addListenerDef(new ListenerDef());
            break;
        case 8:
            ServletDef sd = new ServletDef();
            sd.name = "s";
            d.addServletDef(sd);
            break;
        case 9:
            d.addServletMapping(new ServletMapping());
            break;
        case 10:
            d.setSessionConfig(new SessionConfig());
            break;
        case 11:
            d.addMimeMapping(new MimeMapping());
            break;
        case 12:
            d.welcomeFiles.add("index.html");
            break;
        case 13:
            d.addErrorPage(new ErrorPage());
            break;
        case 14:
            d.jspConfig = new JspConfig();
            break;
        case 15:
            d.addSecurityConstraint(new SecurityConstraint());
            break;
        case 16:
            d.setLoginConfig(new LoginConfig());
            break;
        case 17:
            d.addSecurityRole(new SecurityRole());
            break;
        case 18:
            d.addEnvEntry(new EnvEntry());
            break;
        case 19:
            d.addEjbRef(new EjbRef(true));
            break;
        case 20:
            d.addServiceRef(new ServiceRef());
            break;
        case 21:
            d.addResourceRef(new ResourceRef());
            break;
        case 22:
            d.addResourceEnvRef(new ResourceEnvRef());
            break;
        case 23:
            d.addMessageDestinationRef(new MessageDestinationRef());
            break;
        case 24:
            d.addPersistenceContextRef(new PersistenceContextRef());
            break;
        case 25:
            d.addPersistenceUnitRef(new PersistenceUnitRef());
            break;
        case 26:
            d.addPostConstruct(new LifecycleCallback());
            break;
        case 27:
            d.addPreDestroy(new LifecycleCallback());
            break;
        case 28:
            d.addDataSourceDef(new DataSourceDef());
            break;
        case 29:
            d.addJmsConnectionFactory(new JmsConnectionFactory());
            break;
        case 30:
            d.addJmsDestination(new JmsDestination());
            break;
        case 31:
            d.addMessageDestination(new MessageDestination());
            break;
        case 32:
            d.addMailSession(new MailSession());
            break;
        case 33:
            d.addConnectionFactory(new ConnectionFactory());
            break;
        case 34:
            d.addAdministeredObject(new AdministeredObject());
            break;
        default:
            d.addLocaleEncodingMapping("fr", "ISO-8859-1");
            break;
        }
    }

    @Test
    public void testFragmentOrderingPredicates() {
        WebFragment a = new WebFragment();
        a.name = "a";
        WebFragment b = new WebFragment();
        b.name = "b";
        assertFalse(a.isBefore(b));
        assertFalse(a.isAfter(b));
        assertFalse(a.isBeforeOthers());
        assertFalse(a.isAfterOthers());
        a.before = new java.util.ArrayList<String>();
        a.before.add("b");
        a.before.add(WebFragment.OTHERS);
        a.after = new java.util.ArrayList<String>();
        a.after.add("c");
        a.after.add(WebFragment.OTHERS);
        assertTrue(a.isBefore(b));
        assertTrue(a.isBeforeOthers());
        assertTrue(a.isAfterOthers());
        assertFalse(a.isAfter(b));
    }

    @Test
    public void testEmptyDescriptor() {
        Plain d = new Plain();
        assertTrue(d.isEmpty());
    }

    @Test
    public void testEveryMemberMakesDescriptorNonEmpty() {
        for (int i = 0; i < FIELD_COUNT; i++) {
            Plain d = new Plain();
            populate(d, i);
            assertFalse("member " + i, d.isEmpty());
        }
    }

    @Test
    public void testResetClearsEveryMember() {
        Plain d = new Plain();
        for (int i = 0; i < FIELD_COUNT; i++) {
            populate(d, i);
        }
        d.authentication = true;
        assertFalse(d.isEmpty());
        d.reset();
        assertNull(d.description);
        assertNull(d.jspConfig);
        assertNull(d.loginConfig);
        assertFalse(d.authentication);
        assertTrue(d.contextParams.isEmpty());
        assertTrue(d.servletDefs.isEmpty());
        assertTrue(d.localeEncodingMappings.isEmpty());
        assertTrue(d.getResources().isEmpty());
        assertTrue(d.getInjectables().isEmpty());
    }

    @Test
    public void testMergeCombinesEverything() {
        WebFragment main = new WebFragment();
        WebFragment other = new WebFragment();
        for (int i = 0; i < FIELD_COUNT; i++) {
            populate(other, i);
        }
        main.merge(other);
        assertEquals(1, main.contextParams.size());
        assertEquals(1, main.filterDefs.size());
        assertEquals(1, main.servletDefs.size());
        assertEquals(1, main.filterMappings.size());
        assertEquals(1, main.listenerDefs.size());
        assertEquals(1, main.servletMappings.size());
        assertSame(other.sessionConfig, main.sessionConfig);
        assertSame(other.loginConfig, main.loginConfig);
        assertSame(other.jspConfig, main.jspConfig);
        assertEquals(1, main.mimeMappings.size());
        assertEquals(1, main.welcomeFiles.size());
        assertEquals(1, main.errorPages.size());
        assertEquals(1, main.securityConstraints.size());
        assertEquals(1, main.securityRoles.size());
        assertEquals(1, main.envEntries.size());
        assertEquals(1, main.ejbRefs.size());
        assertEquals(1, main.serviceRefs.size());
        assertEquals(1, main.resourceRefs.size());
        assertEquals(1, main.resourceEnvRefs.size());
        assertEquals(1, main.messageDestinationRefs.size());
        assertEquals(1, main.persistenceContextRefs.size());
        assertEquals(1, main.persistenceUnitRefs.size());
        assertEquals(1, main.postConstructs.size());
        assertEquals(1, main.preDestroys.size());
        assertEquals(1, main.dataSourceDefs.size());
        assertEquals(1, main.jmsConnectionFactories.size());
        assertEquals(1, main.jmsDestinations.size());
        assertEquals(1, main.mailSessions.size());
        assertEquals(1, main.connectionFactories.size());
        assertEquals(1, main.administeredObjects.size());
        assertEquals(1, main.messageDestinations.size());
        assertEquals("ISO-8859-1", main.localeEncodingMappings.get("fr"));
        // merging again keeps first-wins maps stable and the mergeable jsp config
        WebFragment again = new WebFragment();
        for (int i = 0; i < FIELD_COUNT; i++) {
            populate(again, i);
        }
        main.merge(again);
        assertEquals(1, main.contextParams.size());
        assertEquals(1, main.filterDefs.size());
        assertEquals(1, main.servletDefs.size());
        assertEquals(1, main.localeEncodingMappings.size());
        assertEquals(2, main.welcomeFiles.size());
        assertSame(other.sessionConfig, main.sessionConfig);
    }

    @Test
    public void testResourcesAndInjectables() {
        WebFragment d = new WebFragment();
        for (int i = 0; i < FIELD_COUNT; i++) {
            populate(d, i);
        }
        Collection<Resource> resources = d.getResources();
        assertEquals(6, resources.size());
        Collection<Injectable> injectables = d.getInjectables();
        assertEquals(9, injectables.size());
    }

    @Test
    public void testSecurityConstraintTarget() {
        WebFragment d = new WebFragment();
        assertFalse(d.isSecurityConstraintTarget("/a"));
        SecurityConstraint sc = new SecurityConstraint();
        ResourceCollection rc = new ResourceCollection();
        rc.urlPatterns.add("/a");
        sc.addResourceCollection(rc);
        d.addSecurityConstraint(sc);
        assertTrue(d.isSecurityConstraintTarget("/a"));
        assertFalse(d.isSecurityConstraintTarget("/b"));
        assertTrue(d.authentication);
    }

    @Test
    public void testResolveLinksMappings() {
        WebFragment d = new WebFragment();
        FilterDef fd = new FilterDef();
        fd.name = "f";
        d.addFilterDef(fd);
        ServletDef sd = new ServletDef();
        sd.name = "s";
        d.addServletDef(sd);
        FilterMapping fm = new FilterMapping();
        fm.filterName = "f";
        fm.addServletName("s");
        d.addFilterMapping(fm);
        ServletMapping sm = new ServletMapping();
        sm.servletName = "s";
        d.addServletMapping(sm);
        d.resolve();
        assertSame(fd, fm.filterDef);
        assertEquals(1, fm.servletDefs.size());
        assertSame(sd, sm.servletDef);
    }

    @Test
    public void testLoginConfigConvenienceAccessors() {
        WebFragment d = new WebFragment();
        assertNull(d.getAuthMethod());
        assertNull(d.getRealmName());
        assertNull(d.getFormLoginPage());
        assertNull(d.getFormErrorPage());
        LoginConfig lc = new LoginConfig();
        lc.authMethod = "FORM";
        lc.realmName = "r";
        lc.formLoginPage = "/l";
        lc.formErrorPage = "/e";
        d.setLoginConfig(lc);
        assertEquals("FORM", d.getAuthMethod());
        assertEquals("r", d.getRealmName());
        assertEquals("/l", d.getFormLoginPage());
        assertEquals("/e", d.getFormErrorPage());
        assertNotNull(d.loginConfig);
    }

    @Test
    public void testDescriptionAccessors() {
        WebFragment d = new WebFragment();
        d.setDescription("a");
        d.setDisplayName("b");
        d.setSmallIcon("c");
        d.setLargeIcon("d");
        assertEquals("a", d.getDescription());
        assertEquals("b", d.getDisplayName());
        assertEquals("c", d.getSmallIcon());
        assertEquals("d", d.getLargeIcon());
    }

    @Test
    public void testWebFragmentEmptiness() {
        WebFragment f = new WebFragment();
        assertTrue(f.isEmpty());
        f.name = "frag";
        assertFalse(f.isEmpty());
        f.name = null;
        f.servletDefs.put("s", new ServletDef());
        assertFalse(f.isEmpty());
    }

    @Test
    public void testSecurityConstraintTargetChecksAllCollections() {
        Plain d = new Plain();
        SecurityConstraint first = new SecurityConstraint();
        ResourceCollection rc1 = new ResourceCollection();
        rc1.urlPatterns.add("/one");
        first.resourceCollections.add(rc1);
        ResourceCollection rc2 = new ResourceCollection();
        rc2.urlPatterns.add("/two");
        first.resourceCollections.add(rc2);
        SecurityConstraint second = new SecurityConstraint();
        ResourceCollection rc3 = new ResourceCollection();
        rc3.urlPatterns.add("/three");
        second.resourceCollections.add(rc3);
        d.securityConstraints.add(first);
        d.securityConstraints.add(second);
        assertTrue(d.isSecurityConstraintTarget("/one"));
        assertTrue(d.isSecurityConstraintTarget("/two"));
        assertTrue(d.isSecurityConstraintTarget("/three"));
        assertFalse(d.isSecurityConstraintTarget("/four"));
    }

    @Test
    public void testResolveWithUndefinedServletAndFilter() {
        Plain d = new Plain();
        ServletMapping sm = new ServletMapping();
        sm.servletName = "missing";
        d.servletMappings.add(sm);
        FilterMapping fm = new FilterMapping();
        fm.filterName = "nofilter";
        fm.addServletName("missing");
        d.filterMappings.add(fm);
        d.resolve();
        assertNull(sm.servletDef);
        assertNull(fm.filterDef);
        assertTrue(fm.servletDefs.isEmpty());
    }
}
