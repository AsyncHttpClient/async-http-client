package com.ning.http.client.cookiejar;

import java.util.Collection;

import com.ning.http.client.Cookie;
import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.filter.ResponseFilter;

public abstract class AbstractCookieJar
    implements CookieJar
{

    private final RequestFilter requestFilter = new CookieJarRequestFilter( this );

    private final ResponseFilter responseFilter = new CookieJarResponseFilter( this );

    public final RequestFilter getRequestFilter()
    {
        return requestFilter;
    }

    public final ResponseFilter getResponseFilter()
    {
        return responseFilter;
    }

    protected final void store( String host, Cookie cookie )
        throws Exception
    {
        persist( host, cookie );

        String domain = cookie.getDomain();
        if ( !host.equals( domain ) )
        {
            persist( domain, cookie );
        }
    }

    protected abstract void persist( String host, Cookie cookie )
        throws Exception;

    protected abstract Collection<Cookie> retrieve( String host )
        throws Exception;

    protected final void delete( String host, Cookie cookie )
        throws Exception
    {
        remove( host, cookie );

        String domain = cookie.getDomain();
        if ( !host.equals( domain ) )
        {
            remove( domain, cookie );
        }
    }

    protected abstract void remove( String host, Cookie cookie )
        throws Exception;

}
