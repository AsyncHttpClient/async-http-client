package com.ning.http.client.fancy;

import com.ning.http.client.AsyncHandler;


public interface AsyncHandlerFactory<T>
{
    AsyncHandler<T> build();
}
