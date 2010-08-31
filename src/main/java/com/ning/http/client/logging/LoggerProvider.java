package com.ning.http.client.logging;

public interface LoggerProvider
{
    Logger getLogger(Class<?> clazz);
}
