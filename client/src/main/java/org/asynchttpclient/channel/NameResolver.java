/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.channel;

import java.net.InetAddress;
import java.net.UnknownHostException;

public interface NameResolver {

    NameResolution[] resolve(String name) throws UnknownHostException;

    enum JdkNameResolver implements NameResolver {

        INSTANCE;

        @Override
        public NameResolution[] resolve(String name) throws UnknownHostException {
            InetAddress[] addresses = InetAddress.getAllByName(name);
            NameResolution[] resolutions = new NameResolution[addresses.length];
            for (int i = 0; i < addresses.length; i++)
                resolutions[i] = new NameResolution(addresses[i]);
            return resolutions;
        }
    }
}
