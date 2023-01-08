/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.test;

import org.apache.juli.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jJuliLog implements Log {

    private final Logger logger;

    // just so that ServiceLoader doesn't crash, unused
    public Slf4jJuliLog() {
        logger = null;
    }

    // actual constructor
    public Slf4jJuliLog(String name) {
        logger = LoggerFactory.getLogger(name);
    }

    @Override
    public void debug(Object arg0) {
        logger.debug(arg0.toString());
    }

    @Override
    public void debug(Object arg0, Throwable arg1) {
        logger.debug(arg0.toString(), arg1);
    }

    @Override
    public void error(Object arg0) {
        logger.error(arg0.toString());
    }

    @Override
    public void error(Object arg0, Throwable arg1) {
        logger.error(arg0.toString(), arg1);
    }

    @Override
    public void fatal(Object arg0) {
        logger.error(arg0.toString());
    }

    @Override
    public void fatal(Object arg0, Throwable arg1) {
        logger.error(arg0.toString(), arg1);
    }

    @Override
    public void info(Object arg0) {
        logger.info(arg0.toString());
    }

    @Override
    public void info(Object arg0, Throwable arg1) {
        logger.info(arg0.toString(), arg1);
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isFatalEnabled() {
        return logger.isErrorEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    @Override
    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
        return logger.isWarnEnabled();
    }

    @Override
    public void trace(Object arg0) {
        logger.trace(arg0.toString());
    }

    @Override
    public void trace(Object arg0, Throwable arg1) {
        logger.trace(arg0.toString(), arg1);
    }

    @Override
    public void warn(Object arg0) {
        logger.warn(arg0.toString());
    }

    @Override
    public void warn(Object arg0, Throwable arg1) {
        logger.warn(arg0.toString(), arg1);
    }
}
