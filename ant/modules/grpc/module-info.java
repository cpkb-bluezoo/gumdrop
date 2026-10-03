module org.bluezoo.gumdrop.grpc {
    requires java.logging;
    requires transitive org.bluezoo.gumdrop.http;
    requires org.bluezoo.protobuf;
    requires org.bluezoo.gumdrop.mime;

    exports org.bluezoo.gumdrop.grpc;
    exports org.bluezoo.gumdrop.grpc.client;
    exports org.bluezoo.gumdrop.grpc.server;
    exports org.bluezoo.gumdrop.grpc.proto;
}
