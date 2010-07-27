package com.ning.http.client.logging;

public class Log4jLoggerProvider implements LoggerProvider
{
    public Logger getLogger(Class<?> clazz)
    {
        return new Log4jLogger(org.apache.log4j.LogManager.getLogger(clazz));
    }
}
