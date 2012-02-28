package com.ning.http.client.cookiejar;

import java.util.Collection;
import java.util.Timer;

import com.ning.http.client.Cookie;
import com.ning.http.client.filter.RequestFilter;
import com.ning.http.client.filter.ResponseFilter;

public abstract class AbstractCookieJar
    implements CookieJar
{

    private static final int MILLISENCONDS_IN_SECOND = 1000;

    private final RequestFilter requestFilter = new CookieJarRequestFilter( this );

    private final ResponseFilter responseFilter = new CookieJarResponseFilter( this );

    private final Timer timer = new Timer( true );

    public final RequestFilter getRequestFilter()
    {
        return requestFilter;
    }

    public final ResponseFilter getResponseFilter()
    {
        return responseFilter;
    }

    final void store( String host, Cookie cookie )
        throws Exception
    {
        storeAndSchedule( host, cookie );

        String domain = cookie.getDomain();
        if ( !host.equals( domain ) )
        {
            storeAndSchedule( domain, cookie );
        }
    }

    private void storeAndSchedule( String host, Cookie cookie )
        throws Exception
    {
        persist( host, cookie );

        if ( cookie.getMaxAge() > 0 ) // otherwise will be just deleted
        {
            long delay = cookie.getMaxAge() * MILLISENCONDS_IN_SECOND;
            timer.schedule( new DeleteCookieTimerTask( this, host, cookie ), delay );
        }
    }

    protected abstract void persist( String host, Cookie cookie )
        throws Exception;

    protected abstract Collection<Cookie> retrieve( String host )
        throws Exception;

    final void delete( String host, Cookie cookie )
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
