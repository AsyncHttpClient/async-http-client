package com.ning.http.client.cookiejar;

import java.util.TimerTask;

import com.ning.http.client.Cookie;

final class DeleteCookieTimerTask
    extends TimerTask
{

    private final AbstractCookieJar cookieJar;

    private final String host;

    private final Cookie cookie;

    public DeleteCookieTimerTask( AbstractCookieJar cookieJar, String host, Cookie cookie )
    {
        this.cookieJar = cookieJar;
        this.host = host;
        this.cookie = cookie;
    }

    @Override
    public void run()
    {
        try
        {
            cookieJar.remove( host, cookie );
        }
        catch ( Exception e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

}
