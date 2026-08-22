module org.bluezoo.gumdrop.servlet {
    requires java.naming;
    requires javax.servlet.api;

    requires org.bluezoo.gumdrop.core;
    requires org.bluezoo.gumdrop.mime;
    requires org.bluezoo.gumdrop.http;

    exports org.bluezoo.gumdrop.servlet;
    exports org.bluezoo.gumdrop.servlet.jsp;
    exports org.bluezoo.gumdrop.servlet.session;
    exports org.bluezoo.gumdrop.servlet.jndi;
    exports org.bluezoo.gumdrop.servlet.manager;
}
