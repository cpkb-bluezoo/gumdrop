/*
 * MultipartConfigDef.java
 * Copyright (C) 2025 Chris Burdess
 *
 * This file is part of gumdrop, a multipurpose Java server.
 * For more information please visit https://www.nongnu.org/gumdrop/
 *
 * gumdrop is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gumdrop is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gumdrop.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package org.bluezoo.gumdrop.servlet;

import javax.servlet.annotation.MultipartConfig;

/**
 * A multipart-config element in a servlet deployment descriptor.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
class MultipartConfigDef {

    String location = "";
    long maxFileSize = -1L;
    long maxRequestSize = -1L;
    long fileSizeThreshold = 0L;

    void init(MultipartConfig config) {
        location = config.location();
        maxFileSize = config.maxFileSize();
        maxRequestSize = config.maxRequestSize();
        fileSizeThreshold = config.fileSizeThreshold();
    }

}
