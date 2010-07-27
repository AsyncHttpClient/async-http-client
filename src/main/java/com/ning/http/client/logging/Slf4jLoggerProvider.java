package com.ning.http.client.logging;

import org.slf4j.LoggerFactory;

public class Slf4jLoggerProvider implements LoggerProvider
{
    public Logger getLogger(Class<?> clazz)
    {
        return new Slf4jLogger(LoggerFactory.getLogger(clazz));
    }
}
