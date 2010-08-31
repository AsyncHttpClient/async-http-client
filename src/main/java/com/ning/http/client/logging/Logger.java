package com.ning.http.client.logging;

public interface Logger
{
    boolean isDebugEnabled();
    void debug(String msg, Object... msgArgs);
    void debug(Throwable t);
    void debug(Throwable t, String msg, Object... msgArgs);

    void info(String msg, Object... msgArgs);
    void info(Throwable t);
    void info(Throwable t, String msg, Object... msgArgs);

    void warn(String msg, Object... msgArgs);
    void warn(Throwable t);
    void warn(Throwable t, String msg, Object... msgArgs);

    void error(String msg, Object... msgArgs);
    void error(Throwable t);
    void error(Throwable t, String msg, Object... msgArgs);
}
