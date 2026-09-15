/*
 * Gumdrop3NamingRules.java
 * Copyright (C) 2026 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 */

package org.bluezoo.gumdrop.testsupport;

/**
 * Helpers for Gumdrop 3 public type naming (workstream C.1).
 *
 * @see docs/NAMING-TAXONOMY.md
 */
public final class Gumdrop3NamingRules {

    private static final String[][] ACRONYM_PREFIXES = {
            {"Webdav", "WebDAV"},
            {"HTTP3", "Http3"},
            {"HTTP2", "Http2"},
            {"HTTP", "Http"},
            {"SMTP", "Smtp"},
            {"IMAP", "Imap"},
            {"POP3", "Pop3"},
            {"FTP", "Ftp"},
            {"DNSSEC", "Dnssec"},
            {"DNS", "Dns"},
            {"AMQP", "Amqp"},
            {"MQTT", "Mqtt"},
            {"SOCKS", "Socks"},
            {"MDNS", "Mdns"},
            {"GRPC", "Grpc"},
            {"QUIC", "Quic"},
            {"TLS", "Tls"},
            {"UDP", "Udp"},
            {"TCP", "Tcp"},
            {"JWT", "Jwt"},
            {"JCA", "Jca"},
            {"HPACK", "Hpack"},
            {"QPACK", "Qpack"},
            {"LDAP", "Ldap"},
            {"MIME", "Mime"},
            {"JSP", "Jsp"},
            {"OTLP", "Otlp"},
            {"RFC2047", "Rfc2047"},
            {"RFC2231", "Rfc2231"},
            {"RFC", "Rfc"},
            {"BER", "Ber"},
            {"ASN1", "Asn1"},
            {"SASL", "Sasl"},
            {"GSSAPI", "Gssapi"},
            {"RESP", "Resp"},
            {"DKIM", "Dkim"},
            {"DMARC", "Dmarc"},
            {"SPF", "Spf"},
            {"DSN", "Dsn"},
            {"JSSE", "Jsse"},
            {"CIDR", "Cidr"},
            {"SPKI", "Spki"},
            {"DANE", "Dane"},
            {"DAV", "Dav"},
            {"DNSSD", "Dnssd"},
    };

    private Gumdrop3NamingRules() {
    }

    /**
     * Returns true if {@code typeName} uses a legacy Gumdrop 2.x acronym or
     * {@code *Service} application-tier suffix.
     */
    public static boolean isLegacyPublicTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return false;
        }
        String suggested = suggestGumdrop3Name(typeName);
        return suggested != null && !suggested.equals(typeName);
    }

    /**
     * Suggested Gumdrop 3 name for a legacy public type, or {@code null} if
     * the name already conforms.
     */
    public static String suggestGumdrop3Name(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return null;
        }
        for (int i = 0; i < ACRONYM_PREFIXES.length; i++) {
            String legacyPrefix = ACRONYM_PREFIXES[i][0];
            String modernPrefix = ACRONYM_PREFIXES[i][1];
            if (typeName.startsWith(legacyPrefix) && typeName.length() > legacyPrefix.length()) {
                return replaceServiceSuffix(modernPrefix + typeName.substring(legacyPrefix.length()));
            }
        }
        if (typeName.endsWith("Service") && !"Service".equals(typeName)) {
            return typeName.substring(0, typeName.length() - "Service".length()) + "Server";
        }
        if ("Service".equals(typeName)) {
            return "Server";
        }
        if (typeName.startsWith("Server") && typeName.endsWith("Handler")
                && typeName.length() > "Server".length()) {
            return typeName.substring("Server".length());
        }
        if ("RemoteGreeting".equals(typeName)) {
            return "RemoteGreeting";
        }
        return null;
    }

    private static String replaceServiceSuffix(String name) {
        if (name.endsWith("Service")) {
            return name.substring(0, name.length() - "Service".length()) + "Server";
        }
        return name;
    }
}
