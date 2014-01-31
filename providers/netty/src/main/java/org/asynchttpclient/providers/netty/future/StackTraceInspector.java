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
