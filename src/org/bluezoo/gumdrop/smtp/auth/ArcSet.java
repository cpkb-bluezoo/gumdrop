/*
 * ArcSet.java
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

package org.bluezoo.gumdrop.smtp.auth;

/**
 * One ARC instance ({@code i=}) comprising the three header fields defined
 * in RFC 8617 section 4.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ArcHeaderHandler
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8617">RFC 8617 - ARC</a>
 */
public class ArcSet {

    private final int instance;
    private final String authenticationResults;
    private final String messageSignature;
    private final String seal;
    private final ArcCvResult sealCv;
    private final DkimSignature parsedMessageSignature;
    private final DkimSignature parsedSeal;

    /**
     * Creates an ARC set.
     *
     * @param instance the instance number ({@code i=})
     * @param authenticationResults full ARC-Authentication-Results line
     * @param messageSignature full ARC-Message-Signature line
     * @param seal full ARC-Seal line
     * @param sealCv the {@code cv=} value from the seal
     * @param parsedMessageSignature parsed AMS signature tags
     * @param parsedSeal parsed AS signature tags
     */
    public ArcSet(int instance, String authenticationResults,
                  String messageSignature, String seal, ArcCvResult sealCv,
                  DkimSignature parsedMessageSignature,
                  DkimSignature parsedSeal) {
        this.instance = instance;
        this.authenticationResults = authenticationResults;
        this.messageSignature = messageSignature;
        this.seal = seal;
        this.sealCv = sealCv;
        this.parsedMessageSignature = parsedMessageSignature;
        this.parsedSeal = parsedSeal;
    }

    /**
     * Returns this set's instance number ({@code i=}).
     *
     * @return ARC instance, starting at 1
     */
    public int getInstance() {
        return instance;
    }

    /**
     * Returns the raw {@code ARC-Authentication-Results} header line.
     *
     * @return full header including field name, value, and line ending
     */
    public String getAuthenticationResults() {
        return authenticationResults;
    }

    /**
     * Returns the raw {@code ARC-Message-Signature} header line.
     *
     * @return full header including field name, value, and line ending
     */
    public String getMessageSignature() {
        return messageSignature;
    }

    /**
     * Returns the raw {@code ARC-Seal} header line.
     *
     * @return full header including field name, value, and line ending
     */
    public String getSeal() {
        return seal;
    }

    /**
     * Returns the {@code cv=} chain-validation tag from this set's seal.
     *
     * @return seal {@code cv=} value
     */
    public ArcCvResult getSealCv() {
        return sealCv;
    }

    /**
     * Returns parsed tags from the message signature header.
     *
     * @return parsed AMS, or null if the header could not be parsed
     */
    public DkimSignature getParsedMessageSignature() {
        return parsedMessageSignature;
    }

    /**
     * Returns parsed tags from the seal header.
     *
     * @return parsed ARC-Seal, or null if the header could not be parsed
     */
    public DkimSignature getParsedSeal() {
        return parsedSeal;
    }
}
