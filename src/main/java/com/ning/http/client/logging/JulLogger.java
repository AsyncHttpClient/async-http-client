/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.logging;

import java.util.logging.Level;

public class JulLogger implements Logger {
    private final java.util.logging.Logger logger;

    JulLogger(java.util.logging.Logger logger) {
        this.logger = logger;
    }

    public boolean isDebugEnabled() {
        return logger.isLoggable(Level.FINE);
    }

    public void debug(String msg, Object... msgArgs) {
        logger.log(Level.FINE, msg, msgArgs);
    }

    public void debug(Throwable t) {
        logger.log(Level.FINE, t.getMessage(), t);
    }

    public void debug(Throwable t, String msg, Object... msgArgs) {
        logger.log(Level.FINE, String.format(msg, msgArgs), t);
    }

    public void info(String msg, Object... msgArgs) {
        logger.log(Level.INFO, msg, msgArgs);
    }

    public void info(Throwable t) {
        logger.log(Level.INFO, t.getMessage(), t);
    }

    public void info(Throwable t, String msg, Object... msgArgs) {
        logger.log(Level.INFO, String.format(msg, msgArgs), t);
    }

    public void warn(String msg, Object... msgArgs) {
        logger.log(Level.WARNING, msg, msgArgs);
    }

    public void warn(Throwable t) {
        logger.log(Level.WARNING, t.getMessage(), t);
    }

    public void warn(Throwable t, String msg, Object... msgArgs) {
        logger.log(Level.WARNING, String.format(msg, msgArgs), t);
    }

    public void error(String msg, Object... msgArgs) {
        logger.log(Level.SEVERE, msg, msgArgs);
    }

    public void error(Throwable t) {
        logger.log(Level.SEVERE, t.getMessage(), t);
    }

    public void error(Throwable t, String msg, Object... msgArgs) {
        logger.log(Level.SEVERE, String.format(msg, msgArgs), t);
    }
}
