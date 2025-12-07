/*
 * AcceptLanguage.java
 * Copyright (C) 2005 Chris Burdess
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
