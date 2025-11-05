package org.asynchttpclient.filter;

import org.asynchttpclient.AsyncHandler;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Utility class for wrapping {@link AsyncHandler}s to automatically release a semaphore permit
 * upon request completion. This is primarily used by {@link ThrottleRequestFilter} to manage
 * concurrent request limits.
 *
 * <p>The wrapper is implemented using a dynamic proxy to preserve all interfaces
 * of the wrapped handler, ensuring full compatibility with custom handler implementations.</p>
 *
 * <p>The semaphore permit is released when either:</p>
 * <ul>
 *   <li>{@link AsyncHandler#onCompleted()} is called (successful completion)</li>
 *   <li>{@link AsyncHandler#onThrowable(Throwable)} is called (error completion)</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * Semaphore semaphore = new Semaphore(10);
 * AsyncHandler<String> originalHandler = new AsyncCompletionHandler<String>() {
 *     @Override
 *     public String onCompleted(Response response) {
 *         return response.getResponseBody();
 *     }
 * };
 *
 * // Wrap the handler to release semaphore on completion
 * AsyncHandler<String> wrappedHandler = ReleasePermitOnComplete.wrap(originalHandler, semaphore);
 * }</pre>
 */
public class ReleasePermitOnComplete {

  /**
   * Wraps an {@link AsyncHandler} to automatically release a semaphore permit on completion.
   * The permit is released when either {@link AsyncHandler#onCompleted()} or
   * {@link AsyncHandler#onThrowable(Throwable)} is invoked.
   *
   * @param handler the handler to be wrapped
   * @param available the semaphore whose permit will be released on completion
   * @param <T> the handler result type
   * @return the wrapped handler that releases the permit on completion
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
