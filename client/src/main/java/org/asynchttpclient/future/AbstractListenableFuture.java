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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

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

    private volatile ExecutionList executionList;

    private static final AtomicReferenceFieldUpdater<AbstractListenableFuture, ExecutionList> executionListField =
            AtomicReferenceFieldUpdater.newUpdater(AbstractListenableFuture.class, ExecutionList.class, "executionList");

    private ExecutionList executionList() {
        ExecutionList executionListLocal = this.executionList;
        if (executionListLocal != null) {
            return executionListLocal;
        }

        ExecutionList r = new ExecutionList();
        if (executionListField.compareAndSet(this, null, r)) {
            return r;
        } else {
            return this.executionList;
        }
    }

    @Override
    public ListenableFuture<V> addListener(Runnable listener, Executor exec) {
        if (executionList == null) {
            ExecutionList.executeListener(listener, exec);
            return this;
        }

        executionList().add(listener, exec);
        return this;
    }

    /**
     * Execute the execution list.
     */
    protected void runListeners() {
        if (executionList == null) {
            return;
        }

        executionList().execute();
    }
}
