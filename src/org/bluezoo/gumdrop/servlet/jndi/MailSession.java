/*
 * MailSession.java
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

package org.bluezoo.gumdrop.servlet.jndi;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.mail.Authenticator;
import javax.mail.MailSessionDefinition;
import javax.mail.PasswordAuthentication;
import javax.mail.Provider;
import javax.mail.Session;

import org.xml.sax.Attributes;

/**
 * A mail session resource.
 * Corresponds to a <code>mail-session</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class MailSession extends Resource {

    private static final ResourceBundle L10N =
            ResourceBundle.getBundle("org.bluezoo.gumdrop.servlet.jndi.L10N");
    private static final Logger LOGGER = Logger.getLogger(MailSession.class.getName());

    String description;
    String name;
    String storeProtocol = "imap";
    String storeProtocolClass;
    String transportProtocol = "smtp";
    String transportProtocolClass;
    String host;
    String user;
    String password;
    String from;
    Map<String,String> properties = new LinkedHashMap<>();

    // -- Setters and Getters --

    /**
     * Sets a description of this mail session.
     * Corresponds to the {@code description} element within {@code mail-session}
     * in the deployment descriptor.
     *
     * @param description a textual description of this mail session
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Sets the JNDI name by which this mail session will be registered.
     * Corresponds to the {@code name} element in the deployment descriptor.
     * <p>
     * The name must be a valid JNDI name, typically in the form
     * {@code java:comp/env/mail/MySession}.
     *
     * @param name the JNDI name for this mail session
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the protocol used for message store access.
     * Corresponds to the {@code store-protocol} element in the deployment descriptor.
     * <p>
     * Common values are {@code imap}, {@code imaps}, {@code pop3}, {@code pop3s}.
     *
     * @param storeProtocol the mail store protocol
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setStoreProtocol(String storeProtocol) {
        this.storeProtocol = storeProtocol;
    }

    /**
     * Sets the fully-qualified class name of the store protocol provider.
     * Corresponds to the {@code store-protocol-class} element in the deployment descriptor.
     * <p>
     * If not specified, the provider is looked up from the JavaMail providers configuration.
     *
     * @param storeProtocolClass the store protocol provider class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setStoreProtocolClass(String storeProtocolClass) {
        this.storeProtocolClass = storeProtocolClass;
    }

    /**
     * Sets the protocol used for message transport (sending).
     * Corresponds to the {@code transport-protocol} element in the deployment descriptor.
     * <p>
     * Common values are {@code smtp} and {@code smtps}.
     *
     * @param transportProtocol the mail transport protocol
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setTransportProtocol(String transportProtocol) {
        this.transportProtocol = transportProtocol;
    }

    /**
     * Sets the fully-qualified class name of the transport protocol provider.
     * Corresponds to the {@code transport-protocol-class} element in the deployment descriptor.
     * <p>
     * If not specified, the provider is looked up from the JavaMail providers configuration.
     *
     * @param transportProtocolClass the transport protocol provider class name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setTransportProtocolClass(String transportProtocolClass) {
        this.transportProtocolClass = transportProtocolClass;
    }

    /**
     * Sets the mail server hostname.
     * Corresponds to the {@code host} element in the deployment descriptor.
     *
     * @param host the mail server hostname or IP address
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * Sets the user name for mail server authentication.
     * Corresponds to the {@code user} element in the deployment descriptor.
     *
     * @param user the mail server user name
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Sets the password for mail server authentication.
     * Corresponds to the {@code password} element in the deployment descriptor.
     *
     * @param password the mail server password
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Sets the default "from" address for outgoing messages.
     * Corresponds to the {@code from} element in the deployment descriptor.
     * <p>
     * This should be a valid RFC 822 email address.
     *
     * @param from the default from email address
     * @see <a href="https://jakarta.ee/specifications/servlet/4.0/servlet-spec-4.0.html#mail-session">
     *      Servlet 4.0 Specification, Section 5.6: Mail Session</a>
     */
    public void setFrom(String from) {
        this.from = from;
    }

    public void init(MailSessionDefinition config) {
        description = config.description();
        name = config.name();
        storeProtocol = config.storeProtocol();
        transportProtocol = config.transportProtocol();
        host = config.host();
        user = config.user();
        password = config.password();
        from = config.from();
    }

    @Override public void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override public void init(Attributes config) {
        description = config.getValue("description");
        name = config.getValue("name");
        storeProtocol = config.getValue("store-protocol");
        storeProtocolClass = config.getValue("store-protocol-class");
        transportProtocol = config.getValue("transport-protocol");
        transportProtocolClass = config.getValue("transport-protocol-class");
        host = config.getValue("host");
        user = config.getValue("user");
        password = config.getValue("password");
        from = config.getValue("from");
    }

    @Override public String getName() {
        return name;
    }

    @Override public String getClassName() {
        return null; // we will override newInstance in any case
    }

    @Override public String getInterfaceName() {
        return "javax.mail.Session";
    }

    @Override public Object newInstance() {
        Properties props = new Properties();
        props.setProperty("mail.user", user);
        props.setProperty("mail.host", host);
        props.setProperty("mail.password", password);
        props.setProperty("mail.from", from);
        props.setProperty("mail.store.protocol", storeProtocol);
        props.setProperty("mail.transport.protocol", transportProtocol);
        for (Map.Entry<String,String> entry : properties.entrySet()) {
            props.setProperty(entry.getKey(), entry.getValue());
        }
        Authenticator authenticator = this.new MailSessionAuthenticator(new PasswordAuthentication(user, password));
        Session session = Session.getInstance(props, authenticator);
        addProvider(session, storeProtocolClass);
        addProvider(session, transportProtocolClass);
        return session;
    }

    private void addProvider(Session session, String className) {
        if (className == null) {
            return;
        }
        try {
            String interfaceName = "javax.mail.Provider";
            Class t = Class.forName(className);
            Class i = Class.forName(interfaceName);
            if (!i.isAssignableFrom(t)) {
                String message = L10N.getString("err.not_assignable");
                message = MessageFormat.format(message, className, interfaceName);
                throw new InstantiationException(message);
            }
            Provider p = (Provider) t.newInstance();
            session.addProvider(p);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            String message = L10N.getString("err.init_resource");
            message = MessageFormat.format(message, className);
            LOGGER.log(Level.SEVERE, message, e);
        }
    }

    class MailSessionAuthenticator extends Authenticator {

        final PasswordAuthentication passwordAuthentication;

        MailSessionAuthenticator(PasswordAuthentication passwordAuthentication) {
            this.passwordAuthentication = passwordAuthentication;
        }

        protected PasswordAuthentication getPasswordAuthentication() {
            return passwordAuthentication;
        }

    }

}

