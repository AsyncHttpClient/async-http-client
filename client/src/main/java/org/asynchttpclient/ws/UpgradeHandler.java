/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.ws;

/**
 * Invoked when an {@link org.asynchttpclient.AsyncHandler.State#UPGRADE} is returned. Currently the
 * library only support {@link WebSocket} as type.
 *
 * @param <T> the result type
 */
public interface UpgradeHandler<T> {

    /**
     * If the HTTP Upgrade succeed (response's status code equals 101), the
     * {@link org.asynchttpclient.AsyncHttpClient} will invoke that method.
     *
     * @param t an Upgradable entity
     */
    void onSuccess(T t);

    /**
     * If the upgrade fail.
     * 
     * @param t a {@link Throwable}
     */
    void onFailure(Throwable t);
}
