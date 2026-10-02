/*
 * ClientConnectApplyTest.java
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

package org.bluezoo.gumdrop.quic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;

import org.bluezoo.gumdrop.quic.packet.PacketProtection;
import org.bluezoo.gumdrop.quic.packet.PacketProtectionKeys;
import org.bluezoo.gumdrop.quic.packet.QuicAeadAlgorithm;
import org.bluezoo.gumdrop.quic.packet.ShortHeaderCodec;
import org.bluezoo.gumdrop.quic.tls.EncryptionLevel;

/**
 * Test-only access to {@link QuicConnection} internals: reads private
 * fields reflectively and forges protected 1-RTT packets carrying arbitrary
 * frame bytes, so that frame handling can be driven without a second
 * endpoint producing them.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
public final class QuicForger {

    private QuicForger() {
    }

    /**
     * Reads a (possibly private) field.
     *
     * @param target the object
     * @param name the field name
     * @return the value
     * @throws Exception on failure
     */
    public static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * Invokes a (possibly private) method by name and argument count.
     *
     * @param target the object
     * @param name the method name
     * @param args the arguments
     * @return the result
     * @throws Exception on failure
     */
    public static Object invoke(Object target, String name, Object... args) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            Method[] methods = c.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                if (methods[i].getName().equals(name) && methods[i].getParameterTypes().length == args.length) {
                    methods[i].setAccessible(true);
                    return methods[i].invoke(target, args);
                }
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Returns the client engine's single connection.
     *
     * @param engine a client engine
     * @return the connection
     * @throws Exception on failure
     */
    public static QuicConnection clientConnection(QuicEngine engine) throws Exception {
        return (QuicConnection) field(engine, "clientConnection");
    }

    /**
     * Returns the first connection registered with a server engine.
     *
     * @param engine a server engine
     * @return the connection, or null
     * @throws Exception on failure
     */
    public static QuicConnection serverConnection(QuicEngine engine) throws Exception {
        Map<?, ?> map = (Map<?, ?>) field(engine, "connections");
        for (Object o : map.values()) {
            return (QuicConnection) o;
        }
        return null;
    }

    /**
     * Forges a protected 1-RTT datagram from {@code sender}'s keys.
     *
     * @param sender the connection whose send keys are used
     * @param receiver the destination connection
     * @param packetNumber the packet number
     * @param frames the encoded frames (position 0, flipped)
     * @return the datagram
     * @throws Exception on failure
     */
    public static byte[] forge(QuicConnection sender, QuicConnection receiver, long packetNumber,
            ByteBuffer frames) throws Exception {
        Map<?, ?> send = (Map<?, ?>) field(sender, "sendKeys");
        PacketProtectionKeys keys = (PacketProtectionKeys) send.get(EncryptionLevel.ONE_RTT);
        byte[] dcid = receiver.getOurConnectionId();
        int pnLength = 4;
        byte[] header = ShortHeaderCodec.build(dcid, false, packetNumber, pnLength);
        byte[] plain = new byte[frames.remaining()];
        frames.duplicate().get(plain);
        byte[] cipher = PacketProtection.seal(keys, packetNumber, header, plain);
        byte[] packet = new byte[header.length + cipher.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(cipher, 0, packet, header.length, cipher.length);
        int pnOffset = header.length - pnLength;
        byte[] sample = new byte[QuicAeadAlgorithm.SAMPLE_LENGTH];
        System.arraycopy(packet, pnOffset + 4, sample, 0, sample.length);
        byte[] mask = PacketProtection.headerProtectionMask(keys, sample);
        PacketProtection.xorFirstByte(packet, mask, false);
        PacketProtection.xorPacketNumberBytes(packet, pnOffset, pnLength, mask);
        return packet;
    }
}
