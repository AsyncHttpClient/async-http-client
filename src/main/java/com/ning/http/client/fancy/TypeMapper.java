package com.ning.http.client.fancy;

import com.ning.http.client.AsyncCompletionHandlerBase;
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;

import java.util.LinkedHashMap;

public class TypeMapper
{

    private final LinkedHashMap<Class, AsyncHandlerFactory> handlers = new LinkedHashMap<Class, AsyncHandlerFactory>();

    public TypeMapper() {
        handlers.put(Response.class, new AsyncHandlerFactory<Response>() {
            @Override
            public AsyncHandler<Response> build()
            {
                return new AsyncCompletionHandlerBase();
            }
        });

        handlers.put(String.class, new AsyncHandlerFactory<String>() {
            @Override
            public AsyncHandler<String> build()
            {
                return new AsyncHandler<String>() {

                    private final StringBuilder b = new StringBuilder();

                    @Override
                    public void onThrowable(Throwable t)
                    {
                    }

                    @Override
                    public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception
                    {
                        b.append(new String(bodyPart.getBodyPartBytes()));
                        return STATE.CONTINUE;
                    }

                    @Override
                    public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception
                    {
                        return STATE.CONTINUE;
                    }

                    @Override
                    public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception
                    {
                        return STATE.CONTINUE;
                    }

                    @Override
                    public String onCompleted() throws Exception
                    {
                        return b.toString();
                    }
                };
            }
        });

    }

    public <T> AsyncHandler<T> getAsyncHandlerFor(Class<T> crt)
    {
        return handlers.get(crt).build();
    }
}
