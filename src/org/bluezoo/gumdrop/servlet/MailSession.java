/*
 * MailSession.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import javax.mail.Provider;
import javax.mail.Session;

/**
 * A mail session resource.
 * Corresponds to a <code>mail-session</code> element in a
 * deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MailSession extends Resource {

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

    void addProperty(String name, String value) {
        properties.put(name, value);
    }

    @Override String getName() {
        return name;
    }

    @Override String getClassName() {
        return null; // we will override newInstance in any case
    }

    @Override String getInterfaceName() {
        return "javax.mail.Session";
    }

    @Override Object newInstance() {
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
                String message = Context.L10N.getString("err.not_assignable");
                message = MessageFormat.format(message, className, interfaceName);
                throw new InstantiationException(message);
            }
            Provider p = (Provider) t.newInstance();
            session.addProvider(p);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            String message = Context.L10N.getString("err.init_resource");
            message = MessageFormat.format(message, className);
            Context.LOGGER.log(Level.SEVERE, message, e);
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
