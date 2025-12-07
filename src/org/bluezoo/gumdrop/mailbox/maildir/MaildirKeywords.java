/*
 * MaildirKeywords.java
 * Copyright (C) 2025 Chris Burdess
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

package org.bluezoo.gumdrop.mailbox.maildir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages keyword (custom flag) mappings for a Maildir mailbox.
 * 
 * <p>Keywords are stored as lowercase letters a-z in the filename flags.
 * This file maps those letter indices to keyword names.
 * 
 * <p>File format:
 * <pre>
 * # gumdrop-keywords v1
 * 0 $Junk
 * 1 $NotJunk
 * 2 MyLabel
 * </pre>
 * 
 * <p>Maximum of 26 keywords per mailbox (a-z).
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public class MaildirKeywords {

    private static final Logger LOGGER = Logger.getLogger(MaildirKeywords.class.getName());
    private static final ResourceBundle L10N = ResourceBundle.getBundle("org.bluezoo.gumdrop.mailbox.L10N");

    private static final String KEYWORDS_FILENAME = ".keywords";
    private static final String HEADER_LINE = "# gumdrop-keywords v1";
    private static final int MAX_KEYWORDS = 26;

    private final Path maildirPath;
    private final Path keywordsPath;

    /** Maps index (0-25) to keyword name */
    private String[] indexToKeyword;
    
    /** Maps keyword name to index */
    private Map<String, Integer> keywordToIndex;

    private int nextIndex;
    private boolean dirty;

    /**
     * Creates a keywords manager for the specified Maildir.
     *
     * @param maildirPath the path to the Maildir directory
     */
    public MaildirKeywords(Path maildirPath) {
        this.maildirPath = maildirPath;
        this.keywordsPath = maildirPath.resolve(KEYWORDS_FILENAME);
        this.indexToKeyword = new String[MAX_KEYWORDS];
        this.keywordToIndex = new HashMap<>();
        this.nextIndex = 0;
        this.dirty = false;
    }

    /**
     * Loads the keywords file from disk.
     *
     * @throws IOException if the file cannot be read
     */
    public void load() throws IOException {
        indexToKeyword = new String[MAX_KEYWORDS];
        keywordToIndex.clear();
        nextIndex = 0;

        if (!Files.exists(keywordsPath)) {
            return;
        }

        BufferedReader reader = Files.newBufferedReader(keywordsPath, StandardCharsets.UTF_8);
        try {
            String line = reader.readLine();
            
            // Check header
            if (line == null || !line.equals(HEADER_LINE)) {
                String msg = MessageFormat.format(L10N.getString("err.invalid_keywords_header"), maildirPath);
                LOGGER.warning(msg);
                return;
            }

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int spaceIndex = line.indexOf(' ');
                if (spaceIndex > 0) {
                    try {
                        int index = Integer.parseInt(line.substring(0, spaceIndex));
                        String keyword = line.substring(spaceIndex + 1);
                        
                        if (index >= 0 && index < MAX_KEYWORDS) {
                            indexToKeyword[index] = keyword;
                            keywordToIndex.put(keyword, index);
                            if (index >= nextIndex) {
                                nextIndex = index + 1;
                            }
                        }
                    } catch (NumberFormatException e) {
                        String msg = MessageFormat.format(L10N.getString("err.invalid_keyword_index"), line);
                        LOGGER.warning(msg);
                    }
                }
            }
        } finally {
            reader.close();
        }

        dirty = false;
    }

    /**
     * Saves the keywords file to disk.
     *
     * @throws IOException if the file cannot be written
     */
    public void save() throws IOException {
        if (!dirty) {
            return;
        }

        Path tempPath = maildirPath.resolve(KEYWORDS_FILENAME + ".tmp");

        BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8);
        try {
            writer.write(HEADER_LINE);
            writer.newLine();

            for (int i = 0; i < MAX_KEYWORDS; i++) {
                if (indexToKeyword[i] != null) {
                    writer.write(Integer.toString(i));
                    writer.write(' ');
                    writer.write(indexToKeyword[i]);
                    writer.newLine();
                }
            }
        } finally {
            writer.close();
        }

        Files.move(tempPath, keywordsPath, StandardCopyOption.REPLACE_EXISTING);
        dirty = false;
    }

    /**
     * Returns the keyword name for an index.
     *
     * @param index the index (0-25)
     * @return the keyword name, or null if not defined
     */
    public String getKeyword(int index) {
        if (index >= 0 && index < MAX_KEYWORDS) {
            return indexToKeyword[index];
        }
        return null;
    }

    /**
     * Returns the index for a keyword name.
     *
     * @param keyword the keyword name
     * @return the index, or -1 if not found
     */
    public int getIndex(String keyword) {
        Integer index = keywordToIndex.get(keyword);
        return index != null ? index : -1;
    }

    /**
     * Gets or creates an index for a keyword.
     *
     * @param keyword the keyword name
     * @return the index, or -1 if no more slots available
     */
    public int getOrCreateIndex(String keyword) {
        Integer existing = keywordToIndex.get(keyword);
        if (existing != null) {
            return existing;
        }

        if (nextIndex >= MAX_KEYWORDS) {
            String msg = MessageFormat.format(L10N.getString("err.max_keywords_reached"), keyword);
            LOGGER.warning(msg);
            return -1;
        }

        int index = nextIndex++;
        indexToKeyword[index] = keyword;
        keywordToIndex.put(keyword, index);
        dirty = true;
        return index;
    }

    /**
     * Converts a set of keyword names to their indices.
     *
     * @param keywords the keyword names
     * @return set of indices
     */
    public Set<Integer> keywordsToIndices(Set<String> keywords) {
        Set<Integer> indices = new HashSet<>();
        for (String keyword : keywords) {
            int index = getOrCreateIndex(keyword);
            if (index >= 0) {
                indices.add(index);
            }
        }
        return indices;
    }

    /**
     * Converts a set of indices to keyword names.
     *
     * @param indices the indices
     * @return set of keyword names
     */
    public Set<String> indicesToKeywords(Set<Integer> indices) {
        Set<String> keywords = new HashSet<>();
        for (Integer index : indices) {
            String keyword = getKeyword(index);
            if (keyword != null) {
                keywords.add(keyword);
            }
        }
        return keywords;
    }

    /**
     * Returns all defined keywords.
     *
     * @return set of keyword names
     */
    public Set<String> getAllKeywords() {
        return new HashSet<>(keywordToIndex.keySet());
    }

    /**
     * Returns whether the keywords file has been modified.
     *
     * @return true if dirty
     */
    public boolean isDirty() {
        return dirty;
    }

}

