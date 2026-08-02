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
            if (t instanceof IOException) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("Connection reset")) {
                    return true;
                }
            }

            if (isNativeIoException(t)) {
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

    private static boolean isNativeIoException(Throwable t) {
        if (t == null) {
            return false;
        }
        String className = t.getClass().getName();
        if ("io.netty.channel.unix.Errors$NativeIoException".equals(className)) {
            try {
                java.lang.reflect.Method expectedErr = t.getClass().getMethod("expectedErr");
                int errno = (Integer) expectedErr.invoke(t);
                return errno == -54 || errno == -104 || errno == -111;
            } catch (Exception ignore) {
            }
        }
        return false;
    }
}
