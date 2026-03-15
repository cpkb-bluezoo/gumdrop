/*
 * ConnectPacket.java
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

package org.bluezoo.gumdrop.mqtt.codec;

/**
 * MQTT CONNECT packet (type 1).
 *
 * <p>The first packet sent by the client to the server after establishing
 * the network connection. A client MUST send a CONNECT packet as its
 * first packet, and the server MUST treat any other packet received
 * before CONNECT as a protocol violation.
 *
 * <p>The variable header contains the protocol name, protocol level
 * (version), connect flags, and keep-alive interval. The payload
 * contains, in order: the Client Identifier, Will Topic, Will Message,
 * User Name, and Password — each present only if the corresponding
 * connect flag is set.
 *
 * <h3>Connect Flags (byte 8 of variable header)</h3>
 * <table>
 *   <tr><td>Bit 7</td><td>User Name Flag</td></tr>
 *   <tr><td>Bit 6</td><td>Password Flag</td></tr>
 *   <tr><td>Bit 5</td><td>Will Retain</td></tr>
 *   <tr><td>Bits 4–3</td><td>Will QoS</td></tr>
 *   <tr><td>Bit 2</td><td>Will Flag</td></tr>
 *   <tr><td>Bit 1</td><td>Clean Session</td></tr>
 *   <tr><td>Bit 0</td><td>Reserved (must be 0)</td></tr>
 * </table>
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718028">MQTT 3.1.1 §3.1 CONNECT</a>
 * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901033">MQTT 5.0 §3.1 CONNECT</a>
 */
public class ConnectPacket {

    private MQTTProperties properties = MQTTProperties.EMPTY;
    private MQTTVersion version;
    private boolean cleanSession;
    private int keepAlive;
    private String clientId;

    // Will fields
    private boolean willFlag;
    private QoS willQoS;
    private boolean willRetain;
    private String willTopic;
    private byte[] willPayload;
    private MQTTProperties willProperties;

    // Credentials
    private String username;
    private byte[] password;

    /**
     * Creates a new CONNECT packet with default values.
     */
    public ConnectPacket() {
    }

    /**
     * Returns the MQTT 5.0 properties for this packet.
     *
     * @return the properties, never null
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901027">MQTT 5.0 §2.2.2 Properties</a>
     */
    public MQTTProperties getProperties() {
        return properties;
    }

    /**
     * Sets the MQTT 5.0 properties for this packet.
     *
     * @param properties the properties, or null for empty
     */
    public void setProperties(MQTTProperties properties) {
        this.properties = properties != null ? properties : MQTTProperties.EMPTY;
    }

    /**
     * Returns the MQTT protocol version indicated by the Protocol Level
     * field in the variable header.
     *
     * @return the protocol version
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718030">MQTT 3.1.1 §3.1.2.2 Protocol Level</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901037">MQTT 5.0 §3.1.2.2 Protocol Version</a>
     */
    public MQTTVersion getVersion() {
        return version;
    }

    /**
     * Sets the MQTT protocol version.
     *
     * @param version the protocol version
     * @see #getVersion()
     */
    public void setVersion(MQTTVersion version) {
        this.version = version;
    }

    /**
     * Returns the Clean Session flag (bit 1 of the Connect Flags).
     *
     * <p>If true, the client and server MUST discard any previous
     * session state and start a new session. If false, the server
     * MUST resume communication based on the stored session state
     * (subscriptions and pending QoS 1/2 messages).
     *
     * <p>In MQTT 5.0 this is renamed to <em>Clean Start</em>.
     *
     * @return true if a clean session is requested
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.2.4 Clean Session</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901039">MQTT 5.0 §3.1.2.4 Clean Start</a>
     */
    public boolean isCleanSession() {
        return cleanSession;
    }

    /**
     * Sets the Clean Session flag.
     *
     * @param cleanSession true for a clean (new) session
     * @see #isCleanSession()
     */
    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }

    /**
     * Returns the Keep Alive interval in seconds.
     *
     * <p>This is the maximum time interval between control packets
     * sent by the client. If no other packets are sent within this
     * period, the client MUST send a PINGREQ. The server MUST
     * disconnect a client that has not sent a packet within 1.5 times
     * the Keep Alive value.
     *
     * <p>A value of 0 means keep-alive is disabled.
     *
     * @return the keep-alive interval in seconds
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718032">MQTT 3.1.1 §3.1.2.10 Keep Alive</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901045">MQTT 5.0 §3.1.2.10 Keep Alive</a>
     */
    public int getKeepAlive() {
        return keepAlive;
    }

    /**
     * Sets the Keep Alive interval in seconds.
     *
     * @param keepAlive the keep-alive interval (0 to disable)
     * @see #getKeepAlive()
     */
    public void setKeepAlive(int keepAlive) {
        this.keepAlive = keepAlive;
    }

    /**
     * Returns the Client Identifier.
     *
     * <p>The Client Identifier uniquely identifies the client to the
     * server and MUST be present in every CONNECT packet. The server
     * uses this to identify session state. In MQTT 3.1.1, it MUST be
     * between 1 and 23 UTF-8 encoded bytes of characters
     * {@code [0-9a-zA-Z]}, though servers MAY allow longer or richer
     * identifiers.
     *
     * <p>An empty Client Identifier is valid only if Clean Session is
     * true; the server then assigns a unique identifier.
     *
     * @return the client identifier, or null/empty if to be assigned
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.3.1 Client Identifier</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901059">MQTT 5.0 §3.1.3.1 Client Identifier</a>
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Sets the Client Identifier.
     *
     * @param clientId the client identifier
     * @see #getClientId()
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * Returns the Will Flag (bit 2 of the Connect Flags).
     *
     * <p>If true, a Will Message MUST be stored on the server and
     * published to the Will Topic when the connection is closed
     * unexpectedly (without a DISCONNECT packet). The Will Topic
     * and Will Message fields in the payload MUST be present when
     * this flag is set.
     *
     * @return true if a Will Message is specified
     * @see #getWillTopic()
     * @see #getWillPayload()
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.2.5 Will Flag</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901040">MQTT 5.0 §3.1.2.5 Will Flag</a>
     */
    public boolean isWillFlag() {
        return willFlag;
    }

    /**
     * Sets the Will Flag.
     *
     * @param willFlag true to include a Will Message
     * @see #isWillFlag()
     */
    public void setWillFlag(boolean willFlag) {
        this.willFlag = willFlag;
    }

    /**
     * Returns the Will QoS level (bits 4–3 of the Connect Flags).
     *
     * <p>Specifies the QoS level to use when publishing the Will
     * Message. Only meaningful when {@link #isWillFlag()} is true;
     * if the Will Flag is false, this MUST be 0.
     *
     * @return the Will QoS level, or null if not set
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.2.6 Will QoS</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901041">MQTT 5.0 §3.1.2.6 Will QoS</a>
     */
    public QoS getWillQoS() {
        return willQoS;
    }

    /**
     * Sets the Will QoS level.
     *
     * @param willQoS the QoS level for the Will Message
     * @see #getWillQoS()
     */
    public void setWillQoS(QoS willQoS) {
        this.willQoS = willQoS;
    }

    /**
     * Returns the Will Retain flag (bit 5 of the Connect Flags).
     *
     * <p>If true, the Will Message MUST be published as a retained
     * message when the will is triggered. Only meaningful when
     * {@link #isWillFlag()} is true.
     *
     * @return true if the Will Message should be retained
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.2.7 Will Retain</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901042">MQTT 5.0 §3.1.2.7 Will Retain</a>
     */
    public boolean isWillRetain() {
        return willRetain;
    }

    /**
     * Sets the Will Retain flag.
     *
     * @param willRetain true to retain the Will Message
     * @see #isWillRetain()
     */
    public void setWillRetain(boolean willRetain) {
        this.willRetain = willRetain;
    }

    /**
     * Returns the Will Topic.
     *
     * <p>The topic to which the Will Message will be published if
     * the client disconnects ungracefully. Present in the payload
     * only when {@link #isWillFlag()} is true.
     *
     * @return the Will Topic, or null if no will is set
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.3.2 Will Topic</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901069">MQTT 5.0 §3.1.3.3 Will Topic</a>
     */
    public String getWillTopic() {
        return willTopic;
    }

    /**
     * Sets the Will Topic.
     *
     * @param willTopic the topic for the Will Message
     * @see #getWillTopic()
     */
    public void setWillTopic(String willTopic) {
        this.willTopic = willTopic;
    }

    /**
     * Returns the Will Message payload.
     *
     * <p>The application message payload that the server MUST publish
     * to the {@linkplain #getWillTopic() Will Topic} when the will is
     * triggered. In MQTT 3.1.1 this is limited to 65,535 bytes.
     *
     * @return the Will Message payload, or null if no will is set
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.3.3 Will Message</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901070">MQTT 5.0 §3.1.3.4 Will Payload</a>
     */
    public byte[] getWillPayload() {
        return willPayload;
    }

    /**
     * Sets the Will Message payload.
     *
     * @param willPayload the payload for the Will Message
     * @see #getWillPayload()
     */
    public void setWillPayload(byte[] willPayload) {
        this.willPayload = willPayload;
    }

    /**
     * Returns the Will Properties (MQTT 5.0 only).
     *
     * <p>Will Properties appear in the CONNECT payload before the
     * Will Topic when the Will Flag is set. They may include Will
     * Delay Interval, Payload Format Indicator, Message Expiry
     * Interval, Content Type, Response Topic, and Correlation Data.
     *
     * @return the Will Properties, or null if not set
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901060">MQTT 5.0 §3.1.3.2 Will Properties</a>
     */
    public MQTTProperties getWillProperties() {
        return willProperties;
    }

    /**
     * Sets the Will Properties (MQTT 5.0 only).
     *
     * @param willProperties the Will Properties
     * @see #getWillProperties()
     */
    public void setWillProperties(MQTTProperties willProperties) {
        this.willProperties = willProperties;
    }

    /**
     * Returns the User Name.
     *
     * <p>Present in the payload if the User Name Flag (bit 7 of the
     * Connect Flags) is set. Used for authentication; the server
     * MAY use this for authorization as well.
     *
     * @return the user name, or null if not provided
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.2.8 User Name Flag / §3.1.3.4 User Name</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901071">MQTT 5.0 §3.1.3.5 User Name</a>
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the User Name.
     *
     * @param username the user name
     * @see #getUsername()
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the Password.
     *
     * <p>Present in the payload if the Password Flag (bit 6 of the
     * Connect Flags) is set. In MQTT 3.1.1, the User Name Flag
     * MUST be set if the Password Flag is set. In MQTT 5.0, a
     * password may be sent without a user name.
     *
     * @return the password as a byte array, or null if not provided
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc398718031">MQTT 3.1.1 §3.1.2.9 Password Flag / §3.1.3.5 Password</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html#_Toc3901072">MQTT 5.0 §3.1.3.6 Password</a>
     */
    public byte[] getPassword() {
        return password;
    }

    /**
     * Sets the Password.
     *
     * @param password the password as a byte array
     * @see #getPassword()
     */
    public void setPassword(byte[] password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "CONNECT(clientId=" + clientId + ", version=" + version +
                ", cleanSession=" + cleanSession + ", keepAlive=" + keepAlive + ")";
    }
}
