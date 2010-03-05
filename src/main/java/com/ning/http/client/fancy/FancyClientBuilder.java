package com.ning.http.client.fancy;

import com.ning.http.client.AsyncHttpClient;

public class FancyClientBuilder
{
    private final AsyncHttpClient client;

    public FancyClientBuilder(AsyncHttpClient client)
    {
        this.client = client;
    }

    public <T> T build(Class<T> clientClass)
    {
        return null;
    }
}
