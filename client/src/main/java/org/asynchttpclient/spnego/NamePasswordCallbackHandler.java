/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.spnego;

import org.jspecify.annotations.Nullable;
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
    private static final Class<?>[] PASSWORD_CALLBACK_TYPES = new Class<?>[]{Object.class, char[].class, String.class};

    private final String username;
    private final @Nullable String password;
    private final @Nullable String passwordCallbackName;

    public NamePasswordCallbackHandler(String username, @Nullable String password) {
        this(username, password, null);
    }

    public NamePasswordCallbackHandler(String username, @Nullable String password, @Nullable String passwordCallbackName) {
        this.username = username;
        this.password = password;
        this.passwordCallbackName = passwordCallbackName;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (handleCallback(callback)) {
                continue;
            } else if (callback instanceof NameCallback) {
                ((NameCallback) callback).setName(username);
            } else if (callback instanceof PasswordCallback) {
                PasswordCallback pwCallback = (PasswordCallback) callback;
                pwCallback.setPassword(password != null ? password.toCharArray() : null);
            } else if (!invokePasswordCallback(callback)) {
                String errorMsg = "Unsupported callback type " + callback.getClass().getName();
                log.info(errorMsg);
                throw new UnsupportedCallbackException(callback, errorMsg);
            }
        }
    }

    protected boolean handleCallback(Callback callback) {
        return false;
    }

    /*
     * This method is called from the handle(Callback[]) method when the specified callback
     * did not match any of the known callback classes. It looks for the callback method
     * having the specified method name with one of the supported parameter types.
     * If found, it invokes the callback method on the object and returns true.
     * If not, it returns false.
     */
    private boolean invokePasswordCallback(Callback callback) {
        String cbname = passwordCallbackName == null ? PASSWORD_CALLBACK_NAME : passwordCallbackName;
        for (Class<?> arg : PASSWORD_CALLBACK_TYPES) {
            try {
                Method method = callback.getClass().getMethod(cbname, arg);
                Object[] args = {
                        arg == String.class ? password : password != null ? password.toCharArray() : null
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
