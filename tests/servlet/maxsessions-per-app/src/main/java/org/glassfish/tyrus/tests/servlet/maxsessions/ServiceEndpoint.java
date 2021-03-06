/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014-2015 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.tyrus.tests.servlet.maxsessions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.OnMessage;
import javax.websocket.server.ServerEndpoint;

/**
 * Service endpoint to reset a tested endpoint.
 *
 * @author Ondrej Kosatka (ondrej.kosatka at oracle.com)
 */
@ServerEndpoint(value = ServiceEndpoint.SERVICE_ENDPOINT_PATH)
public class ServiceEndpoint {

    public static final String SERVICE_ENDPOINT_PATH = "/service";

    protected static final String POSITIVE = "POSITIVE";
    protected static final String NEGATIVE = "NEGATIVE";

    @OnMessage
    public String onMessage(String message) {

        if (message.startsWith("/echo")) {
            try {
                if (MaxSessionPerAppApplicationConfig.openLatch.await(1, TimeUnit.SECONDS) &&
                        MaxSessionPerAppApplicationConfig.closeLatch.await(1, TimeUnit.SECONDS)) {
                    if (!EchoEndpoint.forbiddenClose.get()) {
                        return POSITIVE;
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return NEGATIVE;
        } else if (message.equals("reset")) {
            reset();
            return POSITIVE;
        }

        return NEGATIVE;
    }

    private void reset() {
        EchoEndpoint.forbiddenClose.set(false);
        MaxSessionPerAppApplicationConfig.openLatch =
                new CountDownLatch(MaxSessionPerAppApplicationConfig.MAX_SESSIONS_PER_APP);
        MaxSessionPerAppApplicationConfig.closeLatch =
                new CountDownLatch(MaxSessionPerAppApplicationConfig.MAX_SESSIONS_PER_APP);
    }
}

