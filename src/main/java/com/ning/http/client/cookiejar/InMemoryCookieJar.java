package com.ning.http.client.cookiejar;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.ning.http.client.Cookie;

public final class InMemoryCookieJar
    extends AbstractCookieJar
{

    private final ConcurrentMap<String, ConcurrentMap<String, Cookie>> cookiesRegistry =
                    new ConcurrentHashMap<String, ConcurrentMap<String, Cookie>>();

    private final Lock lock = new ReentrantLock();

    public void persist( String host, Cookie cookie )
        throws Exception
    {
        lock.lock();

        try
        {
            ConcurrentMap<String, Cookie> domainCookies = cookiesRegistry.get( host );
            if ( domainCookies == null )
            {
                // create the index and store it
                domainCookies = new ConcurrentHashMap<String, Cookie>();
                cookiesRegistry.put( host, domainCookies );
            }

            domainCookies.put( cookie.getName(), cookie );
        }
        finally
        {
            lock.unlock();
        }
    }

    public Collection<Cookie> retrieve( String host )
        throws Exception
    {
        lock.lock();

        try
        {
            ConcurrentMap<String, Cookie> hostCookies = cookiesRegistry.get( host );
            if ( hostCookies != null )
            {
                return hostCookies.values();
            }
            return null;
        }
        finally
        {
            lock.unlock();
        }
    }

    public void remove( String host, Cookie cookie )
        throws Exception
    {
        lock.lock();

        try
        {
            ConcurrentMap<String, Cookie> hostCookies = cookiesRegistry.get( host );
            if ( hostCookies != null )
            {
                hostCookies.remove( cookie.getName() );
            }
        }
        finally
        {
            lock.unlock();
        }
    }

}
