/*
 * AcceptLanguage.java
 * Copyright (C) 2005 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * This software is dual-licensed:
 *
 * 1. GNU General Public License v3 (or later) for open source use
 *    See LICENCE-GPL3 file for GPL terms and conditions.
 *
 * 2. Commercial License for proprietary use
 *    Contact Chris Burdess <dog@gnu.org> for commercial licensing terms.
 *    Mimecast Services Limited has been granted commercial usage rights under
 *    separate license agreement.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.bluezoo.gumdrop.servlet;

import java.util.Locale;

/**
 * Component of an Accept-Language header.
 * @see RFC 2616
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class AcceptLanguage implements Comparable {

    final String spec;
    final double q;

    AcceptLanguage(String spec, double q) {
        this.spec = spec;
        this.q = q;
    }

    Locale toLocale() {
        int hi = spec.indexOf('-');
        if (hi == -1) {
            return new Locale(spec);
        } else {
            String lang = spec.substring(0, hi);
            String country = spec.substring(hi + 1);
            return new Locale(lang, country);
        }
    }

    public int compareTo(Object other) {
        if (other instanceof AcceptLanguage) {
            AcceptLanguage al = (AcceptLanguage) other;
            if (al.q < q) {
                return -1;
            } else if (al.q > q) {
                return 1;
            }
        }
        return 0;
    }

}
