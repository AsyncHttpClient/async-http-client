/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.future;

import io.netty.channel.ConnectTimeoutException;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;

public final class StackTraceInspector {

    private StackTraceInspector() {
        // Prevent outside initialization
    }

    private static boolean exceptionInMethod(Throwable t, String className, String methodName) {
        try {
            for (StackTraceElement element : t.getStackTrace()) {
                if (element.getClassName().equals(className) && element.getMethodName().equals(methodName)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private static boolean recoverOnConnectCloseException(Throwable t) {
        while (true) {
            // Also a ConnectException, but retrying it would multiply the connect timeout.
            if (t instanceof ConnectTimeoutException) {
                return false;
            }
            // The type covers every transport. The frames (checkConnect up to JDK 12, pollConnect after)
            // still matter: NIO reports an unreachable peer as NoRouteToHostException, not a ConnectException.
            if (t instanceof ConnectException
                    || exceptionInMethod(t, "sun.nio.ch.SocketChannelImpl", "checkConnect")
                    || exceptionInMethod(t, "sun.nio.ch.Net", "pollConnect")) {
                return true;
            }
            if (t.getCause() == null) {
                return false;
            }
            t = t.getCause();
        }
    }

    public static boolean recoverOnNettyDisconnectException(Throwable t) {
        // Start at the cause: NettyChannelConnector wraps every failure in a ConnectException.
        return t instanceof ClosedChannelException
                || exceptionInMethod(t, "io.netty.handler.ssl.SslHandler", "disconnect")
                || t.getCause() != null && recoverOnConnectCloseException(t.getCause());
    }

    public static boolean recoverOnReadOrWriteException(Throwable t) {
        while (true) {
            // Native transports (epoll, io_uring, kqueue) report resets as NativeIoException with the
            // strerror text baked into the message, e.g. "recvAddress(..) failed with error(-104):
            // Connection reset by peer". Modern JDKs drop the "by peer" suffix, hence the substring match.
            if (t instanceof IOException) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("Connection reset")) {
                    return true;
                }
            }

            try {
                for (StackTraceElement element : t.getStackTrace()) {
                    String className = element.getClassName();
                    String methodName = element.getMethodName();
                    if ("sun.nio.ch.SocketDispatcher".equals(className) && ("read".equals(methodName) || "write".equals(methodName))) {
                        return true;
                    }
                }
            } catch (Throwable ignore) {
            }

            if (t.getCause() == null) {
                return false;
            }
            t = t.getCause();
        }
    }
}
