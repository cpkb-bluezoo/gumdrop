/*
 * MimeStructureFormat.java
 * Copyright (C) 2026 Chris Burdess
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

package org.bluezoo.gumdrop.imap;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.bluezoo.gumdrop.mime.ContentDisposition;
import org.bluezoo.gumdrop.mime.ContentDispositionParser;
import org.bluezoo.gumdrop.mime.ContentType;
import org.bluezoo.gumdrop.mime.ContentTypeParser;
import org.bluezoo.gumdrop.mime.Parameter;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddress;
import org.bluezoo.gumdrop.mime.rfc5322.EmailAddressParser;
import org.bluezoo.gumdrop.mime.rfc5322.GroupEmailAddress;

/**
 * Formats ENVELOPE, BODY and BODYSTRUCTURE data (RFC 9051 section 7.5.2)
 * from the header fields of a message or MIME entity.
 *
 * <p>All strings handled here are "wire strings": every char is one octet
 * of the final response (ISO-8859-1 mapping). Header values are kept
 * exactly as they appear (RFC 2047 encoded words are not decoded);
 * parameter values decoded by the MIME parsers are re-encoded as UTF-8.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class MimeStructureFormat {

    private MimeStructureFormat() {
    }

    /** Wire form of a Unicode string (UTF-8 octets as chars). */
    static String wire(String text) {
        return new String(text.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.ISO_8859_1);
    }

    private static String unicode(String wire) {
        return new String(wire.getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8);
    }

    /** NIL, a quoted string, or a literal when the octets need one. */
    static String nstring(String wire) {
        if (wire == null) {
            return "NIL";
        }
        boolean quotable = true;
        for (int i = 0; i < wire.length(); i++) {
            char c = wire.charAt(i);
            if (c < 0x20 || c >= 0x7f) {
                quotable = false;
                break;
            }
        }
        if (!quotable) {
            return "{" + wire.length() + "}\r\n" + wire;
        }
        StringBuilder sb = new StringBuilder(wire.length() + 2);
        sb.append('"');
        for (int i = 0; i < wire.length(); i++) {
            char c = wire.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static String upperAtom(String text) {
        return nstring(text.toUpperCase(Locale.ENGLISH));
    }

    private static CharsetDecoder utf8() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    static ContentType contentType(Map<String, String> hdr) {
        String value = hdr.get("content-type");
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        return ContentTypeParser.parse(ByteBuffer.wrap(bytes), utf8());
    }

    private static String parameters(List<Parameter> params) {
        if (params == null || params.isEmpty()) {
            return "NIL";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            Parameter p = params.get(i);
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(upperAtom(p.getName()));
            sb.append(' ');
            sb.append(nstring(wire(p.getValue())));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String typeParameters(ContentType ct, String primary) {
        if (ct == null) {
            return "(\"CHARSET\" \"US-ASCII\")";
        }
        return parameters(ct.getParameters());
    }

    private static String disposition(Map<String, String> hdr) {
        String value = hdr.get("content-disposition");
        if (value == null) {
            return "NIL";
        }
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        ContentDisposition cd = ContentDispositionParser.parse(
                ByteBuffer.wrap(bytes), utf8());
        if (cd == null) {
            return "NIL";
        }
        return "(" + upperAtom(cd.getDispositionType()) + " "
                + parameters(cd.getParameters()) + ")";
    }

    private static String language(Map<String, String> hdr) {
        String value = hdr.get("content-language");
        if (value == null) {
            return "NIL";
        }
        StringTokenizer tokens = new StringTokenizer(value, ", \t");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (tokens.hasMoreTokens()) {
            if (count > 0) {
                sb.append(' ');
            }
            sb.append(nstring(tokens.nextToken()));
            count++;
        }
        if (count == 0) {
            return "NIL";
        }
        if (count == 1) {
            return sb.toString();
        }
        return "(" + sb + ")";
    }

    private static String extension(Map<String, String> hdr, boolean md5) {
        StringBuilder sb = new StringBuilder();
        if (md5) {
            sb.append(' ');
            sb.append(nstring(hdr.get("content-md5")));
        }
        sb.append(' ');
        sb.append(disposition(hdr));
        sb.append(' ');
        sb.append(language(hdr));
        sb.append(' ');
        sb.append(nstring(hdr.get("content-location")));
        return sb.toString();
    }

    /**
     * A multipart body.
     *
     * @param hdr the entity's header fields
     * @param parts the concatenated structures of the child parts
     * @param extended true for BODYSTRUCTURE, false for BODY
     */
    static String multipart(Map<String, String> hdr, String parts,
            boolean extended) {
        ContentType ct = contentType(hdr);
        String sub = ct == null ? "MIXED" : ct.getSubType();
        StringBuilder sb = new StringBuilder("(");
        sb.append(parts);
        sb.append(' ');
        sb.append(upperAtom(sub));
        if (extended) {
            sb.append(' ');
            sb.append(ct == null ? "NIL" : parameters(ct.getParameters()));
            sb.append(extension(hdr, false));
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * A non-multipart body.
     *
     * @param hdr the entity's header fields
     * @param extended true for BODYSTRUCTURE, false for BODY
     * @param size size in octets of the encoded body
     * @param lines size in lines of the encoded body
     * @param innerEnvelope for message/rfc822, the encapsulated envelope
     * @param innerStructure for message/rfc822, the encapsulated body
     */
    static String leaf(Map<String, String> hdr, boolean extended, long size,
            long lines, String innerEnvelope, String innerStructure) {
        ContentType ct = contentType(hdr);
        String primary = ct == null ? "text" : ct.getPrimaryType();
        String sub = ct == null ? "plain" : ct.getSubType();
        String encoding = hdr.get("content-transfer-encoding");
        if (encoding == null || encoding.isEmpty()) {
            encoding = "7BIT";
        }
        StringBuilder sb = new StringBuilder("(");
        sb.append(upperAtom(primary));
        sb.append(' ');
        sb.append(upperAtom(sub));
        sb.append(' ');
        sb.append(typeParameters(ct, primary));
        sb.append(' ');
        sb.append(nstring(hdr.get("content-id")));
        sb.append(' ');
        sb.append(nstring(hdr.get("content-description")));
        sb.append(' ');
        sb.append(upperAtom(encoding));
        sb.append(' ');
        sb.append(size);
        if (innerEnvelope != null) {
            sb.append(' ');
            sb.append(innerEnvelope);
            sb.append(' ');
            sb.append(innerStructure);
            sb.append(' ');
            sb.append(lines);
        } else if (primary.equalsIgnoreCase("text")) {
            sb.append(' ');
            sb.append(lines);
        }
        if (extended) {
            sb.append(extension(hdr, true));
        }
        sb.append(')');
        return sb.toString();
    }

    private static String addressList(String raw) {
        if (raw == null) {
            return null;
        }
        List<EmailAddress> list = EmailAddressParser.parseEmailAddressList(
                unicode(raw), true);
        if (list == null) {
            String bare = raw.trim();
            boolean token = !bare.isEmpty();
            for (int i = 0; i < bare.length(); i++) {
                char c = bare.charAt(i);
                if (c <= ' ' || c == '<' || c == '>' || c == '"' || c == ','
                        || c == '(' || c == ';' || c == ':') {
                    token = false;
                    break;
                }
            }
            if (!token) {
                return null;
            }
            return "((NIL NIL " + nstring(bare) + " NIL))";
        }
        if (list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < list.size(); i++) {
            EmailAddress address = list.get(i);
            if (address instanceof GroupEmailAddress) {
                GroupEmailAddress group = (GroupEmailAddress) address;
                sb.append("(NIL NIL ");
                sb.append(nstring(wire(group.getGroupName())));
                sb.append(" NIL)");
                List<EmailAddress> members = group.getMembers();
                for (int j = 0; members != null && j < members.size(); j++) {
                    appendAddress(sb, members.get(j));
                }
                sb.append("(NIL NIL NIL NIL)");
            } else {
                appendAddress(sb, address);
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private static void appendAddress(StringBuilder sb, EmailAddress address) {
        String name = address.getDisplayName();
        String local = address.getLocalPart();
        String domain = address.getDomain();
        sb.append('(');
        sb.append(name == null || name.isEmpty() ? "NIL" : nstring(wire(name)));
        sb.append(" NIL ");
        sb.append(local == null ? "NIL" : nstring(wire(local)));
        sb.append(' ');
        sb.append(domain == null ? "NIL" : nstring(wire(domain)));
        sb.append(')');
    }

    private static String orNil(String value) {
        return value == null ? "NIL" : value;
    }

    /** The ENVELOPE structure of a message's header fields. */
    static String envelope(Map<String, String> hdr) {
        String from = addressList(hdr.get("from"));
        String sender = addressList(hdr.get("sender"));
        String replyTo = addressList(hdr.get("reply-to"));
        if (sender == null) {
            sender = from;
        }
        if (replyTo == null) {
            replyTo = from;
        }
        StringBuilder sb = new StringBuilder("(");
        sb.append(nstring(hdr.get("date")));
        sb.append(' ');
        sb.append(nstring(hdr.get("subject")));
        sb.append(' ');
        sb.append(orNil(from));
        sb.append(' ');
        sb.append(orNil(sender));
        sb.append(' ');
        sb.append(orNil(replyTo));
        sb.append(' ');
        sb.append(orNil(addressList(hdr.get("to"))));
        sb.append(' ');
        sb.append(orNil(addressList(hdr.get("cc"))));
        sb.append(' ');
        sb.append(orNil(addressList(hdr.get("bcc"))));
        sb.append(' ');
        sb.append(nstring(hdr.get("in-reply-to")));
        sb.append(' ');
        sb.append(nstring(hdr.get("message-id")));
        sb.append(')');
        return sb.toString();
    }
}
