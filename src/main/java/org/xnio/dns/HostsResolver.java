/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.xnio.dns;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import org.xnio.FinishedIoFuture;
import org.xnio.IoFuture;
import org.xnio.dns.record.AaaaRecord;
import org.xnio.dns.record.ARecord;

/**
 * A HOSTS file resolver.
 */
public final class HostsResolver extends AbstractResolver {
    private volatile Map<Domain, List<InetAddress>> hostsMap = Collections.emptyMap();
    private final Resolver next;

    public HostsResolver(final Resolver next) {
        this.next = next;
    }

    private List<InetAddress> newList(InetAddress first) {
        final ArrayList<InetAddress> list = new ArrayList<InetAddress>();
        list.add(first);
        return list;
    }

    private void doInitialize(BufferedReader source) throws IOException {
        Map<Domain, List<InetAddress>> hostsMap = new HashMap<Domain, List<InetAddress>>();
        String line;
        while ((line = source.readLine()) != null) {
            int hi = line.indexOf('#');
            if (hi != -1) {
                line = line.substring(0, hi);
            }
            line = line.trim();
            if (line.length() == 0) {
                continue;
            }
            final String[] parts = line.split("\\s++");
            final int len = parts.length;
            if (len >= 1) {
                String address = parts[0];
                for (int i = 1; i < len; i ++) {
                    final String hostName = parts[i];
                    final Domain domain = Domain.fromString(hostName);
                    final List<InetAddress> list = hostsMap.get(domain);
                    final InetAddress parsed = DNS.parseInetAddress(domain.getHostName(), address);
                    if (list == null) {
                        hostsMap.put(domain, newList(parsed));
                    } else {
                        list.add(parsed);
                    }
                }
            }
        }
        this.hostsMap = hostsMap;
    }

    /**
     * Replace the current mapping with the contents of a new HOSTS file.
     *
     * @param source the hosts file source
     * @throws IOException if an I/O error occurs
     * @throws AddressParseException if an IP address in the hosts file was invalid
     */
    public void initialize(Reader source) throws IOException {
        if (source instanceof BufferedReader) {
            doInitialize((BufferedReader) source);
        } else {
            doInitialize(new BufferedReader(source));
        }
    }

    /**
     * Replace the current mapping with the contents of a new HOSTS file.
     *
     * @param file the file
     * @param encoding the file encoding, or {@code null} to use the platform encoding
     * @throws IOException if an I/O error occurs
     */
    public void initialize(File file, String encoding) throws IOException {
        final FileInputStream is = new FileInputStream(file);
        final InputStreamReader reader = encoding == null ? new InputStreamReader(is) : new InputStreamReader(is, encoding);
        initialize(reader);
    }

    /**
     * Replace the current mapping with the contents of a new HOSTS file.
     *
     * @param fileName the file name
     * @param encoding the file encoding, or {@code null} to use the platform encoding
     * @throws IOException if an I/O error occurs
     */
    public void initialize(String fileName, String encoding) throws IOException {
        initialize(new File(fileName), encoding);
    }

    /**
     * {@inheritDoc}  This instance queries the HOSTS cache, and if no records are found, the request is forwarded to
     * the next resolver in the chain.
     */
    public IoFuture<Answer> resolve(final Domain name, final RRClass rrClass, final RRType rrType, final Set<ResolverFlag> flags) {
        if (rrClass == RRClass.IN || rrClass == RRClass.ANY) {
            final List<InetAddress> list = hostsMap.get(name);
            if (list != null) {
                final Answer.Builder builder = Answer.builder();
                builder.setQueryDomain(name).setQueryRRClass(rrClass).setQueryRRType(rrType).setResultCode(ResultCode.NOERROR);
                for (InetAddress address : list) {
                    if (address instanceof Inet4Address && (rrType == RRType.A || rrType == RRType.ANY)) {
                        builder.addAnswerRecord(new ARecord(name, TTLSpec.ZERO, (Inet4Address) address));
                    } else if (address instanceof Inet6Address && (rrType == RRType.AAAA || rrType == RRType.ANY)) {
                        builder.addAnswerRecord(new AaaaRecord(name, TTLSpec.ZERO, (Inet6Address) address));
                    }
                }
                return new FinishedIoFuture<Answer>(builder.create());
            }
        }
        return next.resolve(name, rrClass, rrType, flags);
    }
}
