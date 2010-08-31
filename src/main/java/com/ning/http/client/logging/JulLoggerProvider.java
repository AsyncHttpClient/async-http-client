package com.ning.http.client.logging;

public class JulLoggerProvider implements LoggerProvider
{
    public Logger getLogger(Class<?> clazz)
    {
        return new JulLogger(java.util.logging.Logger.getLogger(clazz.getName()));
    }
}
