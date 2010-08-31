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

public interface Logger {
    boolean isDebugEnabled();

    void debug(String msg, Object... msgArgs);

    void debug(Throwable t);

    void debug(Throwable t, String msg, Object... msgArgs);

    void info(String msg, Object... msgArgs);

    void info(Throwable t);

    void info(Throwable t, String msg, Object... msgArgs);

    void warn(String msg, Object... msgArgs);

    void warn(Throwable t);

    void warn(Throwable t, String msg, Object... msgArgs);

    void error(String msg, Object... msgArgs);

    void error(Throwable t);

    void error(Throwable t, String msg, Object... msgArgs);
}
