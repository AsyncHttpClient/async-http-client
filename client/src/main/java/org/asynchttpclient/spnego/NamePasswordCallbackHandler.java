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

public class NamePasswordCallbackHandler implements CallbackHandler {
  private final Logger log = LoggerFactory.getLogger(getClass());
  private static final String PASSWORD_CALLBACK_NAME = "setObject";
  private static final Class<?>[] PASSWORD_CALLBACK_TYPES =
      new Class<?>[] {Object.class, char[].class, String.class};

  private String username;
  private String password;

  private String passwordCallbackName;

  public NamePasswordCallbackHandler(String username, String password) {
    this(username, password, null);
  }

  public NamePasswordCallbackHandler(String username, String password, String passwordCallbackName) {
    this.username = username;
    this.password = password;
    this.passwordCallbackName = passwordCallbackName;
  }

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

  protected boolean handleCallback(Callback callback) {
    return false;
  }

  /*
   * This method is called from the handle(Callback[]) method when the specified callback
   * did not match any of the known callback classes. It looks for the callback method
   * having the specified method name with one of the suppported parameter types.
   * If found, it invokes the callback method on the object and returns true.
   * If not, it returns false.
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