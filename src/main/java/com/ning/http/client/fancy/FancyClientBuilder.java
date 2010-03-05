package com.ning.http.client.fancy;

import com.ning.http.client.AsyncHttpClient;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;

public class FancyClientBuilder
{
    private final AsyncHttpClient client;
    private final TypeMapper mapper;

    public FancyClientBuilder(AsyncHttpClient client)
    {
        this.client = client;
        mapper = new TypeMapper();
    }

    public <T> T build(Class<T> clientClass)
    {
        final LinkedHashMap<String, Handler> urls = new LinkedHashMap<String, Handler>();
        final String base;
        if (clientClass.isAnnotationPresent(BaseURL.class)) {
            base = clientClass.getAnnotation(BaseURL.class).value();
        }
        else {
            base = "";
        }

        for (final Method method : clientClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(GET.class)) {
                final GET get = method.getAnnotation(GET.class);
                final String url = base + get.value();
                urls.put(method.toGenericString(), new Handler()
                {
                    private final Class crt;
                    {
                        final Type rt = method.getGenericReturnType();
                        if (rt instanceof ParameterizedType) {
                            final ParameterizedType prt = (ParameterizedType) rt;
                            final Type[] generics = prt.getActualTypeArguments();
                            if (generics.length != 1 || !(generics[0] instanceof Class)) {
                                throw new UnsupportedOperationException("Not Yet Implemented!");
                            }
                            crt = (Class) generics[0];
                        }
                        else {
                            throw new UnsupportedOperationException("Not Yet Implemented!");
                        }
                    }

                    @Override
                    public Object handle(Object[] args) throws IOException
                    {
                        client.prepareGet(url).execute(mapper.getAsyncHandlerFor(crt));
                        return null;
                    }
                });
            }
        }

        return (T) Proxy.newProxyInstance(clientClass.getClassLoader(),
                                          new Class[]{clientClass},
                                          new InvocationHandler()
                                          {
                                              @Override
                                              public Object invoke(Object o, Method method, Object[] objects) throws Throwable
                                              {
                                                  if (urls.containsKey(method.toGenericString())) {
                                                      return urls.get(method.toGenericString()).handle(objects);
                                                  }
                                                  else {
                                                      return method.invoke(o, objects);
                                                  }
                                              }
                                          });
    }


    private interface Handler
    {
        Object handle(Object[] args) throws IOException;
    }
}
