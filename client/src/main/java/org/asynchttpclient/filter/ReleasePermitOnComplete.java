package org.asynchttpclient.filter;

import org.asynchttpclient.AsyncHandler;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Wrapper for {@link AsyncHandler}s to release a permit on {@link AsyncHandler#onCompleted()}. This is done via a dynamic proxy to preserve all interfaces of the wrapped handler.
 */
public class ReleasePermitOnComplete {

  /**
   * Wrap handler to release the permit of the semaphore on {@link AsyncHandler#onCompleted()}.
   *
   * @param handler   the handler to be wrapped
   * @param available the Semaphore to be released when the wrapped handler is completed
   * @param <T>       the handler result type
   * @return the wrapped handler
   */
  @SuppressWarnings("unchecked")
  public static <T> AsyncHandler<T> wrap(final AsyncHandler<T> handler, final Semaphore available) {
    Class<?> handlerClass = handler.getClass();
    ClassLoader classLoader = handlerClass.getClassLoader();
    Class<?>[] interfaces = allInterfaces(handlerClass);

    return (AsyncHandler<T>) Proxy.newProxyInstance(classLoader, interfaces, (proxy, method, args) -> {
        try {
          return method.invoke(handler, args);
        } finally {
          switch (method.getName()) {
            case "onCompleted":
            case "onThrowable":
              available.release();
            default:
          }
        }
    });
  }

  /**
   * Extract all interfaces of a class.
   *
   * @param handlerClass the handler class
   * @return all interfaces implemented by this class
   */
  private static Class<?>[] allInterfaces(Class<?> handlerClass) {
    Set<Class<?>> allInterfaces = new HashSet<>();
    for (Class<?> clazz = handlerClass; clazz != null; clazz = clazz.getSuperclass()) {
      Collections.addAll(allInterfaces, clazz.getInterfaces());
    }
    return allInterfaces.toArray(new Class[allInterfaces.size()]);
  }
}
