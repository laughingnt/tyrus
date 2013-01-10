/*
* DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
*
* Copyright (c) 2011-2013 Oracle and/or its affiliates. All rights reserved.
*
* The contents of this file are subject to the terms of either the GNU
* General Public License Version 2 only ("GPL") or the Common Development
* and Distribution License("CDDL") (collectively, the "License").  You
* may not use this file except in compliance with the License.  You can
* obtain a copy of the License at
* http://glassfish.java.net/public/CDDL+GPL_1_1.html
* or packager/legal/LICENSE.txt.  See the License for the specific
* language governing permissions and limitations under the License.
*
* When distributing the software, include this License Header Notice in each
* file and include the License file at packager/legal/LICENSE.txt.
*
* GPL Classpath Exception:
* Oracle designates this particular file as subject to the "Classpath"
* exception as provided by Oracle in the GPL Version 2 section of the License
* file that accompanied this code.
*
* Modifications:
* If applicable, add the following below the License Header, with the fields
* enclosed by brackets [] replaced by your own identifying information:
* "Portions Copyright [year] [name of copyright owner]"
*
* Contributor(s):
* If you wish your version of this file to be governed by only the CDDL or
* only the GPL Version 2, indicate your decision by adding "[Contributor]
* elects to include this software in this distribution under the [CDDL or GPL
* Version 2] license."  If you don't indicate a single choice of license, a
* recipient has the option to distribute your version of this file under
* either the CDDL, the GPL Version 2 or to extend the choice of license to
* its licensees as provided above.  However, if you add GPL Version 2 code
* and therefore, elected the GPL Version 2 license, then the option applies
* only if the new code is made subject to such option by the copyright
* holder.
*/
package org.glassfish.tyrus.container.grizzly;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.websocket.ClientEndpointConfiguration;
import javax.websocket.CloseReason;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;

import org.glassfish.tyrus.server.TyrusEndpoint;
import org.glassfish.tyrus.server.TyrusRemoteEndpoint;
import org.glassfish.tyrus.spi.SPIEndpoint;
import org.glassfish.tyrus.spi.TyrusClientSocket;
import org.glassfish.tyrus.websockets.DataFrame;
import org.glassfish.tyrus.websockets.Extension;
import org.glassfish.tyrus.websockets.HandShake;
import org.glassfish.tyrus.websockets.HandShakeResponseListener;
import org.glassfish.tyrus.websockets.HandshakeException;
import org.glassfish.tyrus.websockets.ProtocolHandler;
import org.glassfish.tyrus.websockets.WebSocket;
import org.glassfish.tyrus.websockets.WebSocketEngine;
import org.glassfish.tyrus.websockets.WebSocketListener;
import org.glassfish.tyrus.websockets.draft06.ClosingFrame;
import org.glassfish.tyrus.websockets.frametypes.PingFrameType;
import org.glassfish.tyrus.websockets.frametypes.PongFrameType;

import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Processor;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOConnectorHandler;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;

/**
 * Implementation of the WebSocket interface.
 *
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 */
public class GrizzlyClientSocket implements WebSocket, TyrusClientSocket {

    private final URI uri;
    private final ProtocolHandler protocolHandler;
    private final Set<SPIEndpoint> endpoints = Collections.newSetFromMap(new ConcurrentHashMap<SPIEndpoint, Boolean>());
    private TCPNIOTransport transport;
    private final EnumSet<State> connected = EnumSet.range(State.CONNECTED, State.CLOSING);
    private final AtomicReference<State> state = new AtomicReference<State>(State.NEW);
    private final TyrusRemoteEndpoint remoteEndpoint;
    private final long timeoutMs;
    private final ClientEndpointConfiguration clc;
    private Session session = null;

    enum State {
        NEW, CONNECTED, CLOSING, CLOSED
    }

    /**
     * Create new instance.
     *
     * @param uri       endpoint address.
     * @param clc       client endpoint configuration.
     * @param timeoutMs TODO
     */
    public GrizzlyClientSocket(URI uri, ClientEndpointConfiguration clc, long timeoutMs) {
        this.uri = uri;
        this.clc = clc;
        protocolHandler = WebSocketEngine.DEFAULT_VERSION.createHandler(true);
        remoteEndpoint = new TyrusRemoteEndpoint(this);
        this.timeoutMs = timeoutMs;
    }

    /**
     * Connects to the given {@link URI}.
     */
    public void connect() {
        try {
            transport = TCPNIOTransportBuilder.newInstance().build();
            transport.start();

            final TCPNIOConnectorHandler connectorHandler = new TCPNIOConnectorHandler(transport) {
                @Override
                protected void preConfigure(Connection conn) {
                    super.preConfigure(conn);

                    final org.glassfish.tyrus.websockets.Connection connection = getConnection(conn);

                    protocolHandler.setConnection(connection);
                    WebSocketEngine.WebSocketHolder holder = WebSocketEngine.getEngine().setWebSocketHolder(connection, protocolHandler, GrizzlyClientSocket.this);
                    holder.handshake = protocolHandler.createHandShake(uri);
                    prepareHandshake(holder.handshake);
                }
            };

            connectorHandler.setProcessor(createFilterChain());
            connectorHandler.connect(new InetSocketAddress(uri.getHost(), uri.getPort()));
            connectorHandler.setSyncConnectTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace();
            throw new HandshakeException(e.getMessage());
        }
    }

    private void prepareHandshake(HandShake handshake) {
        List<Extension> grizzlyExtensions = new ArrayList<Extension>();

        for (String tyrusExtension : clc.getExtensions()) {
            grizzlyExtensions.add(new Extension(tyrusExtension));
        }

        handshake.setExtensions(grizzlyExtensions);
        handshake.setSubProtocols(clc.getPreferredSubprotocols());

        handshake.setResponseListener(new HandShakeResponseListener() {
            @Override
            public void passResponseHeaders(final Map<String, String> originalHeaders) {
                clc.afterResponse(new HandshakeResponse() {

                    private final Map<String, List<String>> headers =
                            new TreeMap<String, List<String>>(new Comparator<String>() {

                                @Override
                                public int compare(String o1, String o2) {
                                    return o1.toLowerCase().compareTo(o2.toLowerCase());
                                }
                            });

                    @Override
                    public Map<String, List<String>> getHeaders() {
                        for (Map.Entry<String, String> entry : originalHeaders.entrySet()) {
                            headers.put(entry.getKey(), Arrays.asList(entry.getValue()));
                        }
                        return headers;
                    }
                });
            }
        });

        // TODO - remove/refactor after API change (Map<String, List<String>> -> Map<String, String>).
        Map<String, String> headers = handshake.composeRequest().getHeaders();
        Map<String, List<String>> adaptedHeaders = new HashMap<String, List<String>>();

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String value = entry.getValue();
            adaptedHeaders.put(entry.getKey(), value == null ? null : Arrays.asList(value));
        }

        clc.beforeRequest(adaptedHeaders);
        headers.clear();

        for (Map.Entry<String, List<String>> entry : adaptedHeaders.entrySet()) {
            final List<String> value = entry.getValue();
            headers.put(entry.getKey(), value == null ? null : value.get(0));
        }

    }

    /**
     * Add new SPI_Endpoint to the socket.
     *
     * @param endpoint to be added.
     */
    public void addEndpoint(SPIEndpoint endpoint) {
        endpoints.add(endpoint);
        if (session == null) {
            session = endpoint.createSessionForRemoteEndpoint(remoteEndpoint, null, null);
        }
    }

    @Override
    public Future<DataFrame> send(String s) {
        if (isConnected()) {
            return protocolHandler.send(s);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

    @Override
    public Future<DataFrame> send(byte[] bytes) {
        if (isConnected()) {
            return protocolHandler.send(bytes);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

    @Override
    public Future<DataFrame> sendPing(byte[] bytes) {
        DataFrame df = new DataFrame(new PingFrameType(), bytes);
        return this.protocolHandler.send(df);
    }

    @Override
    public Future<DataFrame> sendPong(byte[] bytes) {
        DataFrame df = new DataFrame(new PongFrameType(), bytes);
        return this.protocolHandler.send(df);
    }

    @Override
    public Future<DataFrame> stream(boolean b, String s) {
        if (isConnected()) {
            return protocolHandler.stream(b, s);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }
    }

    @Override
    public Future<DataFrame> stream(boolean b, byte[] bytes, int i, int i1) {

        if (isConnected()) {
            return protocolHandler.stream(b, bytes, i, i1);
        } else {
            throw new RuntimeException("Socket is not connected.");
        }

    }

    @Override
    public void close() {
        close(100000, "Closing");
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public void close(int i) {
        close(i, null);
    }

    @Override
    public void close(int i, String s) {
        if (state.compareAndSet(State.CONNECTED, State.CLOSING)) {
            protocolHandler.doClose();
            if (transport != null) {
                try {
                    transport.stop();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connected.contains(state.get());
    }

    @Override
    public void onConnect() {
        state.set(State.CONNECTED);
        for (SPIEndpoint endpoint : endpoints) {
            endpoint.onConnect(remoteEndpoint, null, null);
        }
    }

    @Override
    public void onMessage(String message) {
        for (SPIEndpoint endpoint : endpoints) {
            endpoint.onMessage(remoteEndpoint, message);
        }
    }

    @Override
    public void onMessage(byte[] bytes) {
        for (SPIEndpoint endpoint : endpoints) {
            endpoint.onMessage(remoteEndpoint, ByteBuffer.wrap(bytes));
        }
    }

    @Override
    public void onFragment(boolean b, String s) {
        for (SPIEndpoint endpoint : endpoints) {
            endpoint.onPartialMessage(remoteEndpoint, s, b);
        }
    }

    @Override
    public void onFragment(boolean bool, byte[] bytes) {
        for (SPIEndpoint endpoint : endpoints) {
            endpoint.onPartialMessage(remoteEndpoint, ByteBuffer.wrap(bytes), bool);
        }
    }

    @Override
    public void onClose(ClosingFrame dataFrame) {
        if (state.compareAndSet(State.CONNECTED, State.CLOSING)) {
            protocolHandler.close(dataFrame.getCode(), dataFrame.getTextPayload());
        } else {
            state.set(State.CLOSED);
            protocolHandler.doClose();
        }
        for (SPIEndpoint endpoint : endpoints) {
            CloseReason closeReason = null;

            if(dataFrame != null) {
                closeReason = new CloseReason(TyrusEndpoint.getCloseCode(dataFrame.getCode()), dataFrame.getReason());
            }
            endpoint.onClose(remoteEndpoint, closeReason);
        }
    }

    @Override
    public void onPing(DataFrame dataFrame) {
        for (SPIEndpoint endpoint : endpoints) {
            endpoint.onPing(remoteEndpoint, ByteBuffer.wrap(dataFrame.getBytes()));
        }
    }

    @Override
    public void onPong(DataFrame dataFrame) {
        for (SPIEndpoint endpoint : endpoints) {
            endpoint.onPong(remoteEndpoint, ByteBuffer.wrap(dataFrame.getBytes()));
        }
    }

    @Override
    public boolean add(WebSocketListener webSocketListener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(WebSocketListener webSocketListener) {
        throw new UnsupportedOperationException();
    }

    private static Processor createFilterChain() {
        FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
        clientFilterChainBuilder.add(new TransportFilter());
        clientFilterChainBuilder.add(new HttpClientFilter());
        clientFilterChainBuilder.add(new WebSocketFilter());
        return clientFilterChainBuilder.build();
    }

    private static org.glassfish.tyrus.websockets.Connection getConnection(final Connection connection) {
        return new ConnectionImpl(connection);
    }
}