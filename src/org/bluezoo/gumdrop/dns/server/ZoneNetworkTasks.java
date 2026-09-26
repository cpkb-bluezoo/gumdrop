/*
 * ZoneNetworkTasks.java
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

package org.bluezoo.gumdrop.dns.server;

import org.bluezoo.gumdrop.SelectorLoop;
import org.bluezoo.gumdrop.dns.DnsMessage;
import org.bluezoo.gumdrop.dns.DnsResourceRecord;
import org.bluezoo.gumdrop.dns.DnsType;
import org.bluezoo.gumdrop.dns.client.DnsZoneClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-blocking zone maintenance over the network (SOA check, AXFR refresh,
 * NOTIFY) on the {@link SelectorLoop} reactor: the production
 * {@link ZoneMasterClient}.
 *
 * @author <a href='mailto:dog@gnu.org'>Chris Burdess</a>
 */
final class ZoneNetworkTasks implements ZoneMasterClient {

    private static final Logger LOGGER = Logger.getLogger(ZoneNetworkTasks.class.getName());

    @Override
    public void querySoaSerial(SelectorLoop loop, InetSocketAddress master, String origin,
            final SerialCallback callback) {
        DnsZoneClient.querySoa(loop, master, origin, DnsZoneClient.DEFAULT_TIMEOUT_MS,
                new DnsZoneClient.MessageCallback() {
                    @Override
                    public void onSuccess(DnsMessage response) {
                        if (response.getRcode() == DnsMessage.RCODE_NOERROR) {
                            List<DnsResourceRecord> answers = response.getAnswers();
                            for (int i = 0; i < answers.size(); i++) {
                                if (answers.get(i).getType() == DnsType.SOA) {
                                    callback.onSerial(answers.get(i).parseSoaFields().serial);
                                    return;
                                }
                            }
                        }
                        callback.onFailure(new IOException("no SOA in response"));
                    }

                    @Override
                    public void onFailure(Exception error) {
                        callback.onFailure(error);
                    }
                });
    }

    @Override
    public void transfer(SelectorLoop loop, final InetSocketAddress master, String origin,
            final DnsZoneClient.TransferCallback callback) {
        DnsZoneClient.axfrOverTcp(loop, master, origin, DnsZoneClient.DEFAULT_TIMEOUT_MS, null, null,
                new DnsZoneClient.TransferCallback() {
                    @Override
                    public void onSuccess(List<DnsResourceRecord> records) {
                        callback.onSuccess(records);
                    }

                    @Override
                    public void onFailure(Exception error) {
                        LOGGER.log(Level.FINE, MessageFormat.format(
                                DnsServer.L10N.getString("fine.zone_axfr_refresh_failed"),
                                master), error);
                        callback.onFailure(error);
                    }
                });
    }

    @Override
    public void notify(SelectorLoop loop, final InetSocketAddress peer, String origin) {
        DnsZoneClient.sendNotify(loop, peer, origin,
                new DnsZoneClient.MessageCallback() {
                    @Override
                    public void onSuccess(DnsMessage ignored) {
                    }

                    @Override
                    public void onFailure(Exception error) {
                        LOGGER.log(Level.FINE, MessageFormat.format(
                                DnsServer.L10N.getString("fine.zone_notify_peer_failed"),
                                peer), error);
                    }
                });
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
