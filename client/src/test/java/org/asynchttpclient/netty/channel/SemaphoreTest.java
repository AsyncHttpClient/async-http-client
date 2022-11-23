package org.asynchttpclient.netty.channel;

import io.github.artsok.RepeatedIfExceptionsTest;
import org.asynchttpclient.exception.TooManyConnectionsException;
import org.asynchttpclient.exception.TooManyConnectionsPerHostException;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SemaphoreTest {

    static final int CHECK_ACQUIRE_TIME__PERMITS = 10;
    static final int CHECK_ACQUIRE_TIME__TIMEOUT = 100;

    static final int NON_DETERMINISTIC__INVOCATION_COUNT = 10;
    static final int NON_DETERMINISTIC__SUCCESS_PERCENT = 70;

    private final Object PK = new Object();

    public Object[][] permitsAndRunnersCount() {
        Object[][] objects = new Object[100][];
        int row = 0;
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                objects[row++] = new Object[]{i, j};
            }
        }
        return objects;
    }

    @ParameterizedTest
    @MethodSource("permitsAndRunnersCount")
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void maxConnectionCheckPermitCount(int permitCount, int runnerCount) {
        allSemaphoresCheckPermitCount(new MaxConnectionSemaphore(permitCount, 0), permitCount, runnerCount);
    }

    @ParameterizedTest
    @MethodSource("permitsAndRunnersCount")
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void perHostCheckPermitCount(int permitCount, int runnerCount) {
        allSemaphoresCheckPermitCount(new PerHostConnectionSemaphore(permitCount, 0), permitCount, runnerCount);
    }

    @ParameterizedTest
    @MethodSource("permitsAndRunnersCount")
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void combinedCheckPermitCount(int permitCount, int runnerCount) {
        allSemaphoresCheckPermitCount(new CombinedConnectionSemaphore(permitCount, permitCount, 0), permitCount, runnerCount);
        allSemaphoresCheckPermitCount(new CombinedConnectionSemaphore(0, permitCount, 0), permitCount, runnerCount);
        allSemaphoresCheckPermitCount(new CombinedConnectionSemaphore(permitCount, 0, 0), permitCount, runnerCount);
    }

    private void allSemaphoresCheckPermitCount(ConnectionSemaphore semaphore, int permitCount, int runnerCount) {
        List<SemaphoreRunner> runners = IntStream.range(0, runnerCount)
                .mapToObj(i -> new SemaphoreRunner(semaphore, PK))
                .collect(Collectors.toList());
        runners.forEach(SemaphoreRunner::acquire);
        runners.forEach(SemaphoreRunner::await);

        long tooManyConnectionsCount = runners.stream().map(SemaphoreRunner::getAcquireException)
                .filter(Objects::nonNull)
                .filter(e -> e instanceof IOException)
                .count();

        long acquired = runners.stream().map(SemaphoreRunner::getAcquireException)
                .filter(Objects::isNull)
                .count();

        int expectedAcquired = permitCount > 0 ? Math.min(permitCount, runnerCount) : runnerCount;

        assertEquals(expectedAcquired, acquired);
        assertEquals(runnerCount - acquired, tooManyConnectionsCount);
    }

    @RepeatedTest(NON_DETERMINISTIC__INVOCATION_COUNT)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void maxConnectionCheckAcquireTime() {
        checkAcquireTime(new MaxConnectionSemaphore(CHECK_ACQUIRE_TIME__PERMITS, CHECK_ACQUIRE_TIME__TIMEOUT));
    }

    @RepeatedTest(NON_DETERMINISTIC__INVOCATION_COUNT)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void perHostCheckAcquireTime() {
        checkAcquireTime(new PerHostConnectionSemaphore(CHECK_ACQUIRE_TIME__PERMITS, CHECK_ACQUIRE_TIME__TIMEOUT));
    }

    @RepeatedTest(NON_DETERMINISTIC__INVOCATION_COUNT)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void combinedCheckAcquireTime() {
        checkAcquireTime(new CombinedConnectionSemaphore(CHECK_ACQUIRE_TIME__PERMITS,
                CHECK_ACQUIRE_TIME__PERMITS,
                CHECK_ACQUIRE_TIME__TIMEOUT));
    }

    private void checkAcquireTime(ConnectionSemaphore semaphore) {
        List<SemaphoreRunner> runners = IntStream.range(0, CHECK_ACQUIRE_TIME__PERMITS * 2)
                .mapToObj(i -> new SemaphoreRunner(semaphore, PK))
                .collect(Collectors.toList());
        long acquireStartTime = System.currentTimeMillis();
        runners.forEach(SemaphoreRunner::acquire);
        runners.forEach(SemaphoreRunner::await);
        long timeToAcquire = System.currentTimeMillis() - acquireStartTime;

        assertTrue(timeToAcquire >= CHECK_ACQUIRE_TIME__TIMEOUT - 50, "Semaphore acquired too soon: " + timeToAcquire + " ms"); //Lower Bound
        assertTrue(timeToAcquire <= CHECK_ACQUIRE_TIME__TIMEOUT + 300, "Semaphore acquired too late: " + timeToAcquire + " ms"); //Upper Bound
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void maxConnectionCheckRelease() throws IOException {
        checkRelease(new MaxConnectionSemaphore(1, 0));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void perHostCheckRelease() throws IOException {
        checkRelease(new PerHostConnectionSemaphore(1, 0));
    }

    @RepeatedIfExceptionsTest(repeats = 5)
    @Timeout(unit = TimeUnit.MILLISECONDS, value = 1000)
    public void combinedCheckRelease() throws IOException {
        checkRelease(new CombinedConnectionSemaphore(1, 1, 0));
    }

    private void checkRelease(ConnectionSemaphore semaphore) throws IOException {
        semaphore.acquireChannelLock(PK);
        boolean tooManyCaught = false;
        try {
            semaphore.acquireChannelLock(PK);
        } catch (TooManyConnectionsException | TooManyConnectionsPerHostException e) {
            tooManyCaught = true;
        }
        assertTrue(tooManyCaught);
        tooManyCaught = false;
        semaphore.releaseChannelLock(PK);
        try {
            semaphore.acquireChannelLock(PK);
        } catch (TooManyConnectionsException | TooManyConnectionsPerHostException e) {
            tooManyCaught = true;
        }
        assertFalse(tooManyCaught);
    }
}
