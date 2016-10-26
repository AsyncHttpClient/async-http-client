package org.asynchttpclient.future;

import java.util.ArrayList;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @author Stepan Koltsov
 */
public class RunnableExecutorPairTest {

    @Test
    public void testReverseList() {
        // empty
        {
            Assert.assertNull(RunnableExecutorPair.reverseList(null));
        }

        for (int len = 1; len < 5; ++len) {
            ArrayList<RunnableExecutorPair> list = new ArrayList<>();
            for (int i = 0; i < len; ++i) {
                RunnableExecutorPair prev = i != 0 ? list.get(i - 1) : null;
                list.add(new RunnableExecutorPair(() -> {}, null, prev));
            }

            RunnableExecutorPair reversed = RunnableExecutorPair.reverseList(list.get(list.size() - 1));
            for (int i = 0; i < len; ++i) {
                Assert.assertSame(reversed, list.get(i));
                Assert.assertSame(i != len - 1 ? list.get(i + 1) : null, reversed.next);
                reversed = reversed.next;
            }
        }
    }

}
