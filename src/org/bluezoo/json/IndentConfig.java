/*
 * IndentConfig.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of jsonparser, a JSON parsing library for Java.
 * For more information please visit https://github.com/cpkb-bluezoo/jsonparser/
 *
 * jsonparser is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * jsonparser is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with jsonparser.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.json;

/**
 * Configuration for JSON output indentation.
 * <p>
 * Specifies the character to use for indentation (space or tab) and
 * how many times to repeat it per indentation level.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class IndentConfig {

    private final char indentChar;
    private final int indentCount;

    /**
     * Creates an indent configuration.
     *
     * @param indentChar the character to use for indentation (' ' or '\t')
     * @param indentCount how many times to repeat the character per level (must be positive)
     * @throws IllegalArgumentException if indentChar is not space or tab, or if count is not positive
     */
    public IndentConfig(char indentChar, int indentCount) {
        if (indentChar != ' ' && indentChar != '\t') {
            throw new IllegalArgumentException("Indent character must be space or tab");
        }
        if (indentCount <= 0) {
            throw new IllegalArgumentException("Indent count must be positive");
        }
        this.indentChar = indentChar;
        this.indentCount = indentCount;
    }

    /**
     * Returns the character to use for indentation.
     *
     * @return the indent character (' ' or '\t')
     */
    public char getIndentChar() {
        return indentChar;
    }

    /**
     * Returns how many times to repeat the indent character per level.
     *
     * @return the indent count
     */
    public int getIndentCount() {
        return indentCount;
    }

    /**
     * Creates an indent configuration using tabs.
     *
     * @return configuration for single tab per level
     */
    public static IndentConfig tabs() {
        return new IndentConfig('\t', 1);
    }

    /**
     * Creates an indent configuration using 2 spaces per level.
     *
     * @return configuration for 2 spaces per level
     */
    public static IndentConfig spaces2() {
        return new IndentConfig(' ', 2);
    }

    /**
     * Creates an indent configuration using 4 spaces per level.
     *
     * @return configuration for 4 spaces per level
     */
    public static IndentConfig spaces4() {
        return new IndentConfig(' ', 4);
    }

    /**
     * Creates an indent configuration using the specified number of spaces per level.
     *
     * @param count the number of spaces per level
     * @return configuration for the specified number of spaces per level
     */
    public static IndentConfig spaces(int count) {
        return new IndentConfig(' ', count);
    }

    @Override
    public String toString() {
        if (indentChar == '\t') {
            return "IndentConfig(tab x " + indentCount + ")";
        } else {
            return "IndentConfig(" + indentCount + " spaces)";
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof IndentConfig)) {
            return false;
        }
        IndentConfig other = (IndentConfig) obj;
        return indentChar == other.indentChar && indentCount == other.indentCount;
    }

    @Override
    public int hashCode() {
        return 31 * indentChar + indentCount;
    }
}

