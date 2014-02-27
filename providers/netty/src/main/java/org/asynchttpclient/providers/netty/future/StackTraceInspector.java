/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.providers.netty.future;

public class StackTraceInspector {

    private static boolean exceptionInMethod(Throwable cause, String className, String methodName) {
        try {
            for (StackTraceElement element : cause.getStackTrace()) {
                if (element.getClassName().equals(className) && element.getMethodName().equals(methodName))
                    return true;
            }
        } catch (Throwable t) {
        }
        return false;
    }

    private static boolean abortOnConnectCloseException(Throwable cause) {

        if (exceptionInMethod(cause, "sun.nio.ch.SocketChannelImpl", "checkConnect"))
            return true;

        if (cause.getCause() != null)
            return abortOnConnectCloseException(cause.getCause());

        return false;
    }

    public static boolean abortOnDisconnectException(Throwable cause) {

        if (exceptionInMethod(cause, "io.netty.handler.ssl.SslHandler", "disconnect"))
            return true;

        if (cause.getCause() != null)
            return abortOnConnectCloseException(cause.getCause());

        return false;
    }

    public static boolean abortOnReadCloseException(Throwable cause) {

        if (exceptionInMethod(cause, "sun.nio.ch.SocketDispatcher", "read"))
            return true;

        if (cause.getCause() != null)
            return abortOnReadCloseException(cause.getCause());

        return false;
    }

    public static boolean abortOnWriteCloseException(Throwable cause) {

        if (exceptionInMethod(cause, "sun.nio.ch.SocketDispatcher", "write"))
            return true;

        if (cause.getCause() != null)
            return abortOnWriteCloseException(cause.getCause());

        return false;
    }
}
