package org.asynchttpclient.filter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.asynchttpclient.AsyncHandler;

/**
 * Wrapper for {@link AsyncHandler}s to release a permit on {@link AsyncHandler#onCompleted()}.
 * This is done via a dynamic proxy to preserve all interfaces of the wrapped handler.
 */
public class ReleasePermitOnComplete {
   /**
    * Wrap handler to release the permit of the semaphore on {@link AsyncHandler#onCompleted()}.
    */
   public static <T> AsyncHandler<T> wrap(final AsyncHandler<T> handler, final Semaphore available) {
      Class<?> handlerClass = handler.getClass();
      ClassLoader classLoader = handlerClass.getClassLoader();
      Class<?>[] interfaces = allInterfaces(handlerClass);

      return (AsyncHandler<T>) Proxy.newProxyInstance(classLoader, interfaces, new InvocationHandler() {
         @Override
         public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
               return method.invoke(handler, args);
            } finally {
               if ("onCompleted".equals(method.getName())) {
                  available.release();
               }
            }
         }
      });
   }

   /**
    * Extract all interfaces of a class.
    */
   static Class<?>[] allInterfaces(Class<?> handlerClass) {
      Set<Class<?>> allInterfaces = new HashSet<>();
      Class<?> clazz = handlerClass;
      while (clazz != null) {
         Class<?>[] interfaces = clazz.getInterfaces();
         if (interfaces.length != 0) {
            allInterfaces.addAll(Arrays.asList(interfaces));
         }
         clazz = clazz.getSuperclass();
      }
      return allInterfaces.toArray(new Class[allInterfaces.size()]);
   }
}
