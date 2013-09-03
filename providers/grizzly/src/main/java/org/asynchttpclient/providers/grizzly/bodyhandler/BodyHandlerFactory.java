/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly.bodyhandler;

import org.asynchttpclient.Request;
import org.asynchttpclient.providers.grizzly.GrizzlyAsyncHttpProvider;

public final class BodyHandlerFactory {

    private final BodyHandler[] handlers;

    public BodyHandlerFactory(GrizzlyAsyncHttpProvider grizzlyAsyncHttpProvider) {
        handlers = new BodyHandler[]{
                new StringBodyHandler(grizzlyAsyncHttpProvider),
                new ByteArrayBodyHandler(grizzlyAsyncHttpProvider),
                new ParamsBodyHandler(grizzlyAsyncHttpProvider),
                new StreamDataBodyHandler(),
                new PartsBodyHandler(),
                new FileBodyHandler(),
                new BodyGeneratorBodyHandler()
        };
    }

    public BodyHandler getBodyHandler(final Request request) {
        for (int i = 0, len = handlers.length; i < len; i++) {
            final BodyHandler h = handlers[i];
            if (h.handlesBodyType(request)) {
                return h;
            }
        }
        return new NoBodyHandler();
    }

} // END BodyHandlerFactory
