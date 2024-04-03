/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
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

import java.io.IOException;
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
            if (exceptionInMethod(t, "sun.nio.ch.SocketChannelImpl", "checkConnect")) {
                return true;
            }
            if (t.getCause() == null) {
                return false;
            }
            t = t.getCause();
        }
    }

    public static boolean recoverOnNettyDisconnectException(Throwable t) {
        return t instanceof ClosedChannelException
                || exceptionInMethod(t, "io.netty.handler.ssl.SslHandler", "disconnect")
                || t.getCause() != null && recoverOnConnectCloseException(t.getCause());
    }

    public static boolean recoverOnReadOrWriteException(Throwable t) {
        while (true) {
            if (t instanceof IOException && "Connection reset by peer".equalsIgnoreCase(t.getMessage())) {
                return true;
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
