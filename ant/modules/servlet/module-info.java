module org.bluezoo.gumdrop.servlet {
    requires java.logging;
    requires java.naming;
    requires java.sql;
    requires java.xml;
    requires java.desktop;
    requires java.compiler;

    requires transitive jakarta.servlet;
    requires transitive org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    requires org.bluezoo.gonzalez;
    requires org.bluezoo.protobuf;

    // Referenced by name for annotation, JNDI and persistence injection;
    // optional at run time.
    requires static jakarta.annotation;
    requires static jakarta.persistence;
    requires static java.annotation;
    requires static java.persistence;
    requires static jakarta.mail;
    requires static javax.ejb.api;

    exports org.bluezoo.gumdrop.servlet;
    exports org.bluezoo.gumdrop.servlet.jsp;
    exports org.bluezoo.gumdrop.servlet.session;
    exports org.bluezoo.gumdrop.servlet.jndi;
    exports org.bluezoo.gumdrop.servlet.manager;
    exports org.bluezoo.gumdrop.servlet.server;
    exports jakarta.servlet.jsp;
}
