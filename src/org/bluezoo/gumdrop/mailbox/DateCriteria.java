/*
 * DateCriteria.java
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

package org.bluezoo.gumdrop.mailbox;

import java.io.IOException;
import java.time.LocalDate;

/**
 * A {@link SearchCriteria} matching messages by internal or sent date, as
 * produced by {@link SearchCriteria#before}/{@link SearchCriteria#on}/
 * {@link SearchCriteria#since} (internal date) and {@link
 * SearchCriteria#sentBefore}/{@link SearchCriteria#sentOn}/{@link
 * SearchCriteria#sentSince} (sent date).
 *
 * <p>A named, inspectable class rather than an anonymous one (issue
 * #304) so {@code org.bluezoo.gumdrop.mailbox.index.MessageIndex} can
 * recognise it and answer it from its date sub-indexes instead of
 * scanning every message. Constructed only by {@link SearchCriteria}'s
 * own factory methods.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class DateCriteria implements SearchCriteria {

    /** Which of a message's two dates this criteria compares against. */
    public enum Field { INTERNAL, SENT }

    /** The comparison this criteria performs against {@link #getDate()}. */
    public enum Comparison { BEFORE, ON, SINCE }

    private final Field field;
    private final Comparison comparison;
    private final LocalDate date;

    DateCriteria(Field field, Comparison comparison, LocalDate date) {
        this.field = field;
        this.comparison = comparison;
        this.date = date;
    }

    /**
     * Returns which of a message's dates this criteria compares against.
     *
     * @return the date field
     */
    public Field getField() {
        return field;
    }

    /**
     * Returns the comparison this criteria performs.
     *
     * @return the comparison
     */
    public Comparison getComparison() {
        return comparison;
    }

    /**
     * Returns the date being compared against.
     *
     * @return the date
     */
    public LocalDate getDate() {
        return date;
    }

    @Override
    public boolean matches(MessageContext context) throws IOException {
        LocalDate msgDate = (field == Field.INTERNAL)
                ? context.getInternalLocalDate() : context.getSentLocalDate();
        if (msgDate == null) {
            return false;
        }
        switch (comparison) {
            case BEFORE: return msgDate.isBefore(date);
            case ON: return msgDate.equals(date);
            case SINCE: return !msgDate.isBefore(date);
            default: return false;
        }
    }

    @Override
    public String toString() {
        return "DateCriteria[" + field + " " + comparison + " " + date + "]";
    }
}
