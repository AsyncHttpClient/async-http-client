package com.ning.http.client.logging;

public class Slf4jLogger implements Logger
{
    private final org.slf4j.Logger logger;

    Slf4jLogger(org.slf4j.Logger logger)
    {
        this.logger = logger;
    }

    public boolean isDebugEnabled()
    {
        return logger.isDebugEnabled();
    }

    public void debug(String msg, Object... msgArgs)
    {
        logger.debug(String.format(msg, msgArgs));
    }

    public void debug(Throwable t)
    {
        logger.debug(t.getMessage(), t);
    }

    public void debug(Throwable t, String msg, Object... msgArgs)
    {
        logger.debug(String.format(msg, msgArgs), t);
    }

    public void info(String msg, Object... msgArgs)
    {
        logger.info(String.format(msg, msgArgs));
    }

    public void info(Throwable t)
    {
        logger.info(t.getMessage(), t);
    }

    public void info(Throwable t, String msg, Object... msgArgs)
    {
        logger.info(String.format(msg, msgArgs), t);
    }

    public void warn(String msg, Object... msgArgs)
    {
        logger.warn(String.format(msg, msgArgs));
    }

    public void warn(Throwable t)
    {
        logger.warn(t.getMessage(), t);
    }

    public void warn(Throwable t, String msg, Object... msgArgs)
    {
        logger.warn(String.format(msg, msgArgs), t);
    }

    public void error(String msg, Object... msgArgs)
    {
        logger.error(String.format(msg, msgArgs));
    }

    public void error(Throwable t)
    {
        logger.error(t.getMessage(), t);
    }

    public void error(Throwable t, String msg, Object... msgArgs)
    {
        logger.error(String.format(msg, msgArgs), t);
    }
}
