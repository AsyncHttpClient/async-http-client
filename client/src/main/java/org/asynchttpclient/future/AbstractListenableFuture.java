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
/*
 * Copyright (C) 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.asynchttpclient.future;

import java.util.concurrent.Executor;

import org.asynchttpclient.ListenableFuture;

/**
 * An abstract base implementation of the listener support provided by {@link ListenableFuture}. This class uses an {@link ExecutionList} to guarantee that all registered listeners
 * will be executed. Listener/Executor pairs are stored in the execution list and executed in the order in which they were added, but because of thread scheduling issues there is
 * no guarantee that the JVM will execute them in order. In addition, listeners added after the task is complete will be executed immediately, even if some previously added
 * listeners have not yet been executed.
 *
 * @author Sven Mawson
 * @since 1
 */
public abstract class AbstractListenableFuture<V> implements ListenableFuture<V> {

    private volatile boolean hasRun;
    private volatile boolean executionListInitialized;
    private volatile ExecutionList executionList;

    private ExecutionList executionList() {
        ExecutionList localExecutionList = executionList;
        if (localExecutionList == null) {
            synchronized (this) {
                localExecutionList = executionList;
                if (localExecutionList == null) {
                    localExecutionList = new ExecutionList();
                    executionList = localExecutionList;
                    executionListInitialized = true;
                }
            }
        }
        return localExecutionList;
    }

    @Override
    public ListenableFuture<V> addListener(Runnable listener, Executor exec) {
        executionList().add(listener, exec);
        if (hasRun) {
            runListeners();
        }
        return this;
    }

    /**
     * Execute the execution list.
     */
    protected void runListeners() {
        hasRun = true;
        if (executionListInitialized) {
            executionList().execute();
        }
    }
}
