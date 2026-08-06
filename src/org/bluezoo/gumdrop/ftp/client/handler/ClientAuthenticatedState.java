/*
 * ClientAuthenticatedState.java
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

package org.bluezoo.gumdrop.ftp.client.handler;

/**
 * Operations available once the FTP session is authenticated.
 * RFC 959 §4.1.2 (TYPE/STRU/MODE), §4.1.3 (CWD/CDUP/PWD/DELE/RMD/MKD).
 *
 * <p>Data-transfer commands (RETR/STOR/APPE/LIST/NLST/MLSD, and the
 * PASV/EPSV/PORT/EPRT commands that establish the data connection) are
 * added separately once the data-connection coordinator is in place.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see ServerPassReplyHandler#handleAuthenticated
 * @see ServerAcctReplyHandler#handleAuthenticated
 * @see <a href="https://www.rfc-editor.org/rfc/rfc959">RFC 959</a>
 */
public interface ClientAuthenticatedState {

    /**
     * Sends a CWD command to change the working directory.
     * RFC 959 §4.1.3.
     *
     * @param pathname the directory to change into
     * @param callback receives the server's response
     */
    void cwd(String pathname, ServerCwdReplyHandler callback);

    /**
     * Sends a CDUP command to change to the parent directory.
     * RFC 959 §4.1.3.
     *
     * @param callback receives the server's response
     */
    void cdup(ServerSimpleReplyHandler callback);

    /**
     * Sends a PWD command to query the current working directory.
     * RFC 959 §4.1.3.
     *
     * @param callback receives the server's response
     */
    void pwd(ServerPwdReplyHandler callback);

    /**
     * Sends a TYPE command to set the representation type.
     * RFC 959 §4.1.2 (e.g. "A" for ASCII, "I" for image/binary).
     *
     * @param type the representation type parameters, e.g. {@code "A"} or
     *      {@code "I"}
     * @param callback receives the server's response
     */
    void type(String type, ServerSimpleReplyHandler callback);

    /**
     * Sends a STRU command to set the file structure.
     * RFC 959 §4.1.2 (e.g. "F" for file, "R" for record, "P" for page).
     *
     * @param structure the file structure code
     * @param callback receives the server's response
     */
    void stru(String structure, ServerSimpleReplyHandler callback);

    /**
     * Sends a MODE command to set the transfer mode.
     * RFC 959 §4.1.2 (e.g. "S" for stream, "B" for block, "C" for
     * compressed).
     *
     * @param mode the transfer mode code
     * @param callback receives the server's response
     */
    void mode(String mode, ServerSimpleReplyHandler callback);

    /**
     * Sends a DELE command to delete a remote file.
     * RFC 959 §4.1.3.
     *
     * @param pathname the file to delete
     * @param callback receives the server's response
     */
    void dele(String pathname, ServerSimpleReplyHandler callback);

    /**
     * Sends an RMD command to remove a remote directory.
     * RFC 959 §4.1.3.
     *
     * @param pathname the directory to remove
     * @param callback receives the server's response
     */
    void rmd(String pathname, ServerSimpleReplyHandler callback);

    /**
     * Sends an MKD command to create a remote directory.
     * RFC 959 §4.1.3.
     *
     * @param pathname the directory to create
     * @param callback receives the server's response
     */
    void mkd(String pathname, ServerMkdReplyHandler callback);

    /**
     * Closes the connection gracefully.
     *
     * <p>Sends a QUIT command and closes the connection.
     */
    void quit();

}
