/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.tyrus;


import org.junit.Assert;
import org.junit.Test;

import javax.websocket.*;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Future;

/**
 * Tests the RemoteEndpointWrapper.
 *
 * @author Stepan Kopriva (stepan.kopriva at oracle.com)
 */
public class RemoteEndpointWrapperTest {

    private final byte[] sentBytes = {'a', 'b', 'c'};
    private final String sentString = "abc";

    @Test
    public void testGetSendStream() throws IOException {
        TestRemoteEndpoint tre = new TestRemoteEndpoint();
        SessionImpl testSession = new SessionImpl(null, null, null, null, null, true, null, null, Collections.<String, String>emptyMap());
        RemoteEndpointWrapper rew = new RemoteEndpointWrapper(testSession, tre, null);
        OutputStream stream = rew.getSendStream();

        for (byte b : sentBytes) {
            stream.write(b);
        }

        stream.flush();

        Assert.assertArrayEquals("Writing bytes one by one to stream and flushing.", sentBytes, tre.getBytesAndClearBuffer());

        stream.write(sentBytes);
        stream.flush();

        Assert.assertArrayEquals("Writing byte[] to stream and flushing.", sentBytes, tre.getBytesAndClearBuffer());
    }


    @Test
    public void testGetSendWriter() throws IOException {
        char[] toSend = sentString.toCharArray();
        TestRemoteEndpoint tre = new TestRemoteEndpoint();
        SessionImpl testSession = new SessionImpl(null, null, null, null, null, true, null, null, Collections.<String, String>emptyMap());
        RemoteEndpointWrapper rew = new RemoteEndpointWrapper(testSession, tre, null);
        Writer writer = rew.getSendWriter();

        writer.write(toSend, 0, 3);
        writer.flush();
        Assert.assertEquals("Writing the whole message.", sentString, tre.getStringAndCleanBuilder());

        writer.write(toSend, 0, 1);
        writer.flush();
        Assert.assertEquals("Writing first character.", String.valueOf(toSend[0]), tre.getStringAndCleanBuilder());

        writer.write(toSend, 2, 1);
        writer.flush();
        Assert.assertEquals("Writing first character.", String.valueOf(toSend[2]), tre.getStringAndCleanBuilder());
    }


    private class TestRemoteEndpoint implements RemoteEndpoint {

        private ArrayList<Byte> bytesToSend = new ArrayList<Byte>();
        StringBuilder builder = new StringBuilder();

        @Override
        public void sendString(String text) throws IOException {

        }

        @Override
        public void sendBytes(ByteBuffer data) throws IOException {

        }

        @Override
        public void sendPartialString(String fragment, boolean isLast) throws IOException {
            builder.append(fragment);
        }

        @Override
        public void sendPartialBytes(ByteBuffer partialByte, boolean isLast) throws IOException {
            byte[] bytes = partialByte.array();
            for (byte b : bytes) {
                bytesToSend.add(b);
            }
        }

        @Override
        public OutputStream getSendStream() throws IOException {
            return null;
        }

        @Override
        public Writer getSendWriter() throws IOException {
            return null;
        }

        @Override
        public void sendObject(Object o) throws IOException, EncodeException {

        }

        @Override
        public void sendStringByCompletion(String text, SendHandler completion) {

        }

        @Override
        public Future<SendResult> sendStringByFuture(String text) {
            return null;
        }

        @Override
        public Future<SendResult> sendBytesByFuture(ByteBuffer data) {
            return null;
        }

        @Override
        public void sendBytesByCompletion(ByteBuffer data, SendHandler completion) {

        }

        @Override
        public Future<SendResult> sendObjectByFuture(Object o) {
            return null;
        }

        @Override
        public void sendObjectByCompletion(Object o, SendHandler handler) {

        }

        @Override
        public void sendPing(ByteBuffer applicationData) {

        }

        @Override
        public void sendPong(ByteBuffer applicationData) {

        }

        public byte[] getBytesAndClearBuffer() {
            byte[] result = new byte[bytesToSend.size()];

            for (int i = 0; i < bytesToSend.size(); i++) {
                result[i] = bytesToSend.get(i);
            }

            bytesToSend.clear();
            return result;
        }

        public String getStringAndCleanBuilder() {
            String result = builder.toString();
            builder = new StringBuilder();
            return result;
        }
    }

}