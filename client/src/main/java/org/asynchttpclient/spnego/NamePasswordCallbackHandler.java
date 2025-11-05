package org.asynchttpclient.spnego;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Callback handler that provides username and password for JAAS authentication.
 * <p>
 * This implementation handles standard {@link NameCallback} and {@link PasswordCallback}
 * callbacks, as well as custom password callbacks through reflection. It is primarily
 * used for Kerberos/SPNEGO authentication where credentials need to be provided
 * programmatically.
 * </p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * CallbackHandler handler = new NamePasswordCallbackHandler("username", "password");
 * LoginContext loginContext = new LoginContext("MyContext", handler);
 * loginContext.login();
 * }</pre>
 */
public class NamePasswordCallbackHandler implements CallbackHandler {
  private final Logger log = LoggerFactory.getLogger(getClass());
  private static final String PASSWORD_CALLBACK_NAME = "setObject";
  private static final Class<?>[] PASSWORD_CALLBACK_TYPES =
      new Class<?>[] {Object.class, char[].class, String.class};

  private String username;
  private String password;

  private String passwordCallbackName;

  /**
   * Creates a new callback handler with the specified username and password.
   *
   * @param username the username to provide in callbacks
   * @param password the password to provide in callbacks
   */
  public NamePasswordCallbackHandler(String username, String password) {
    this(username, password, null);
  }

  /**
   * Creates a new callback handler with the specified username, password, and custom
   * password callback method name.
   *
   * @param username the username to provide in callbacks
   * @param password the password to provide in callbacks
   * @param passwordCallbackName the name of the method to invoke for custom password callbacks
   *                            (can be null to use the default "setObject")
   */
  public NamePasswordCallbackHandler(String username, String password, String passwordCallbackName) {
    this.username = username;
    this.password = password;
    this.passwordCallbackName = passwordCallbackName;
  }

  /**
   * Handles the specified callbacks by providing username and password information.
   * <p>
   * This method supports:
   * </p>
   * <ul>
   *   <li>{@link NameCallback} - provides the username</li>
   *   <li>{@link PasswordCallback} - provides the password as a character array</li>
   *   <li>Custom callbacks - invokes password setter methods through reflection</li>
   * </ul>
   *
   * @param callbacks the callbacks to handle
   * @throws IOException if an I/O error occurs
   * @throws UnsupportedCallbackException if a callback type is not supported
   */
  public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
    for (int i = 0; i < callbacks.length; i++) {
      Callback callback = callbacks[i];
      if (handleCallback(callback)) {
        continue;
      } else if (callback instanceof NameCallback) {
        ((NameCallback) callback).setName(username);
      } else if (callback instanceof PasswordCallback) {
        PasswordCallback pwCallback = (PasswordCallback) callback;
        pwCallback.setPassword(password.toCharArray());
      } else if (!invokePasswordCallback(callback)) {
        String errorMsg = "Unsupported callback type " + callbacks[i].getClass().getName();
        log.info(errorMsg);
        throw new UnsupportedCallbackException(callbacks[i], errorMsg);
      }
    }
  }

  /**
   * Extension point for subclasses to handle custom callback types.
   * <p>
   * This method is called before standard callback handling. Subclasses can override
   * this method to provide custom callback handling logic.
   * </p>
   *
   * @param callback the callback to handle
   * @return {@code true} if the callback was handled, {@code false} otherwise
   */
  protected boolean handleCallback(Callback callback) {
    return false;
  }

  /**
   * Invokes a password setter method on the callback object through reflection.
   * <p>
   * This method is called when the callback doesn't match any of the standard types.
   * It looks for a method with the configured name (or default "setObject") that accepts
   * one of the supported parameter types (Object, char[], or String).
   * </p>
   *
   * @param callback the callback on which to invoke the password setter
   * @return {@code true} if a matching method was found and invoked, {@code false} otherwise
   */
  private boolean invokePasswordCallback(Callback callback) {
    String cbname = passwordCallbackName == null
        ? PASSWORD_CALLBACK_NAME : passwordCallbackName;
    for (Class<?> arg : PASSWORD_CALLBACK_TYPES) {
      try {
        Method method = callback.getClass().getMethod(cbname, arg);
        Object args[] = new Object[] {
            arg == String.class ? password : password.toCharArray()
        };
        method.invoke(callback, args);
        return true;
      } catch (Exception e) {
        // ignore and continue
        log.debug(e.toString());
      }
    }
    return false;
  }
}