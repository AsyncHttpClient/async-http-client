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
 * An abstract base implementation of the listener support provided by {@link ListenableFuture}.
 * Listener/Executor pairs are stored in the {@link RunnableExecutorPair} linked list in the order in which they were added, but because of thread scheduling issues there is
 * no guarantee that the JVM will execute them in order. In addition, listeners added after the task is complete will be executed immediately, even if some previously added
 * listeners have not yet been executed.
 *
 * @author Sven Mawson
 * @since 1
 */
public abstract class AbstractListenableFuture<V> implements ListenableFuture<V> {

    /**
     * Marks that execution is already done, and new runnables
     * should be executed right away instead of begin added to the list.
     */
    private static final RunnableExecutorPair executedMarker = new RunnableExecutorPair();

    /**
     * Linked list of executions or a {@link #executedMarker}.
     */
    private volatile RunnableExecutorPair executionList;
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<AbstractListenableFuture, RunnableExecutorPair> executionListField =
            AtomicReferenceFieldUpdater.newUpdater(AbstractListenableFuture.class, RunnableExecutorPair.class, "executionList");

    @Override
    public ListenableFuture<V> addListener(Runnable listener, Executor exec) {
        for (;;) {
            RunnableExecutorPair executionListLocal = this.executionList;
            if (executionListLocal == executedMarker) {
                RunnableExecutorPair.executeListener(listener, exec);
                return this;
            }

            RunnableExecutorPair pair = new RunnableExecutorPair(listener, exec, executionListLocal);
            if (executionListField.compareAndSet(this, executionListLocal, pair)) {
                return this;
            }
        }
    }

    /**
     * Execute the execution list.
     */
    protected void runListeners() {
        RunnableExecutorPair execution = executionListField.getAndSet(this, executedMarker);
        if (execution == executedMarker) {
            return;
        }

        RunnableExecutorPair reversedList = RunnableExecutorPair.reverseList(execution);

        while (reversedList != null) {
            RunnableExecutorPair.executeListener(reversedList.runnable, reversedList.executor);
            reversedList = reversedList.next;
        }
    }
}
