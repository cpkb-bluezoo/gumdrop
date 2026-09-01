/*
 * ConnectIpTarget.java
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

package org.bluezoo.gumdrop.http;

/**
 * The target/ipproto scope-limiting hint encoded in an RFC 9484
 * CONNECT-IP request's {@code :path}, per the URI Template (RFC 6570)
 * registered in RFC 9484 section 3:
 *
 * <pre>{@code
 * /.well-known/masque/ip/{target}/{ipproto}/
 * }</pre>
 *
 * <p>Both variables are optional in the template and, per section 4.6,
 * an omitted or literal {@code "*"} value means "unspecified" (any
 * target / any IP protocol) -- the common case for IP proxying, unlike
 * RFC 9298 CONNECT-UDP where the target is always a concrete host/port.
 * {@code target} itself, per section 4.6's ABNF ({@code IPv6prefix /
 * IPv4prefix / reg-name / "*"}), may be a hostname, an IPv4 or IPv6
 * address optionally followed by a prefix length (its {@code "/"}
 * percent-encoded as {@code "%2F"}, and an IPv6 address's colons
 * percent-encoded as {@code "%3A"}) -- this class decodes the segment
 * but does not itself further parse which of those three shapes it is;
 * that's a policy/forwarding-layer concern (see {@link ConnectIpPolicy}).
 * {@code ipproto} is a decimal Internet Protocol Number in {@code
 * [0, 255]}, or {@code "*"}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9484#section-3">RFC 9484 section 3</a>
 */
public final class ConnectIpTarget {

    /** The RFC 9484 section 3 URI Template's fixed prefix. */
    public static final String PATH_PREFIX = "/.well-known/masque/ip/";

    /** The wildcard value for either variable: "unspecified" (RFC 9484 section 4.6). */
    public static final String WILDCARD = "*";

    private final String target;
    private final String ipProto;

    private ConnectIpTarget(String target, String ipProto) {
        this.target = target;
        this.ipProto = ipProto;
    }

    /**
     * Returns the target, exactly as decoded from the request path:
     * {@link #WILDCARD}, a hostname, or an IP address/prefix.
     *
     * @return the target
     */
    public String getTarget() {
        return target;
    }

    /**
     * Returns whether {@link #getTarget} is the wildcard (unspecified) value.
     *
     * @return true if the target is unspecified
     */
    public boolean isTargetUnspecified() {
        return WILDCARD.equals(target);
    }

    /**
     * Returns the IP protocol, exactly as decoded from the request
     * path: {@link #WILDCARD}, or a decimal Internet Protocol Number.
     *
     * @return the IP protocol
     */
    public String getIpProto() {
        return ipProto;
    }

    /**
     * Returns whether {@link #getIpProto} is the wildcard (unspecified) value.
     *
     * @return true if the IP protocol is unspecified
     */
    public boolean isIpProtoUnspecified() {
        return WILDCARD.equals(ipProto);
    }

    /**
     * Parses a CONNECT-IP request path against the RFC 9484 section 3
     * URI Template.
     *
     * @param path the request's {@code :path} pseudo-header value
     * @return the parsed target, or {@code null} if {@code path} does
     *         not match the template
     */
    public static ConnectIpTarget parse(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith("/")) {
            return null;
        }
        String rest = path.substring(PATH_PREFIX.length(), path.length() - 1);
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String targetSegment = rest.substring(0, slash);
        String ipProtoSegment = rest.substring(slash + 1);
        if (targetSegment.isEmpty() || ipProtoSegment.isEmpty() || ipProtoSegment.indexOf('/') >= 0) {
            return null;
        }
        String target = WILDCARD.equals(targetSegment)
                ? WILDCARD : UriSegmentCodec.percentDecode(targetSegment);
        if (target == null) {
            return null;
        }
        String ipProto;
        if (WILDCARD.equals(ipProtoSegment)) {
            ipProto = WILDCARD;
        } else {
            int value;
            try {
                value = Integer.parseInt(ipProtoSegment);
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 0 || value > 255) {
                return null;
            }
            ipProto = ipProtoSegment;
        }
        return new ConnectIpTarget(target, ipProto);
    }

    /**
     * Encodes a target/ipproto pair into a CONNECT-IP request path
     * matching the RFC 9484 section 3 URI Template.
     *
     * @param target {@link #WILDCARD} for "unspecified", or a hostname
     *               or IP address/prefix (percent-encoded as needed)
     * @param ipProto {@link #WILDCARD} for "unspecified", or a decimal
     *                Internet Protocol Number in {@code [0, 255]}
     * @return the encoded {@code :path} value
     * @throws IllegalArgumentException if {@code ipProto} is neither
     *         {@link #WILDCARD} nor a valid Internet Protocol Number
     */
    public static String encode(String target, String ipProto) {
        String encodedTarget = WILDCARD.equals(target)
                ? WILDCARD : UriSegmentCodec.percentEncode(target);
        String encodedIpProto;
        if (WILDCARD.equals(ipProto)) {
            encodedIpProto = WILDCARD;
        } else {
            int value;
            try {
                value = Integer.parseInt(ipProto);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid IP protocol: " + ipProto);
            }
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("IP protocol out of range: " + ipProto);
            }
            encodedIpProto = ipProto;
        }
        return PATH_PREFIX + encodedTarget + "/" + encodedIpProto + "/";
    }
}
