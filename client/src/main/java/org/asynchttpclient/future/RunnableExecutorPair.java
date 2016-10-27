package org.asynchttpclient.future;

import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.asynchttpclient.util.Assertions;

/**
 * Linked list of runnables with executors.
 */
final class RunnableExecutorPair {
    private static final Logger log = Logger.getLogger(RunnableExecutorPair.class.getPackage().getName());

    final Runnable runnable;
    final Executor executor;
    RunnableExecutorPair next;

    RunnableExecutorPair() {
        runnable = null;
        executor = null;
    }

    RunnableExecutorPair(Runnable runnable, Executor executor, RunnableExecutorPair next) {
        Assertions.assertNotNull(runnable, "runnable");

        this.runnable = runnable;
        this.executor = executor;
        this.next = next;
    }

    /**
     * Submits the given runnable to the given {@link Executor} catching and logging all {@linkplain RuntimeException runtime exceptions} thrown by the executor.
     */
    static void executeListener(Runnable runnable, Executor executor) {
        try {
            if (executor != null) {
                executor.execute(runnable);
            } else {
                runnable.run();
            }
        } catch (RuntimeException e) {
            // Log it and keep going, bad runnable and/or executor. Don't punish the other runnables if
            // we're given a bad one. We only catch RuntimeException because we want Errors to propagate
            // up.
            log.log(Level.SEVERE, "RuntimeException while executing runnable " + runnable + " with executor " + executor, e);
        }
    }

    static RunnableExecutorPair reverseList(RunnableExecutorPair list) {
        // The pairs in the stack are in the opposite order from how they were added
        // so we need to reverse the list to fulfill our contract.
        // This is somewhat annoying, but turns out to be very fast in practice. Alternatively, we
        // could drop the contract on the method that enforces this queue like behavior since depending
        // on it is likely to be a bug anyway.

        // N.B. All writes to the list and the next pointers must have happened before the above
        // synchronized block, so we can iterate the list without the lock held here.
        RunnableExecutorPair prev = null;

        while (list != null) {
            RunnableExecutorPair next = list.next;
            list.next = prev;
            prev = list;
            list = next;
        }

        return prev;
    }
}
