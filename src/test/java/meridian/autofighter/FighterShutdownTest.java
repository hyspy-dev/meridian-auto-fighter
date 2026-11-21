package meridian.autofighter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import meridian.core.api.Movement;
import meridian.core.api.Navigator;
import org.slf4j.LoggerFactory;

/** A module may release its class loader only after every offloaded job has returned. */
public final class FighterShutdownTest {
    public static void main(String[] args) throws Exception {
        List<Runnable> queued = new ArrayList<>();
        Fighter fighter = fighter(unavailable(Movement.class), queued::add);
        CompletableFuture<?> first = fighter.guard(Fighter.Options.DEFAULT);
        CompletableFuture<?> second = fighter.guard(Fighter.Options.DEFAULT);
        CompletableFuture<Void> shutdown = CompletableFuture.runAsync(fighter::shutdown);
        try {
            shutdown.get(200, TimeUnit.MILLISECONDS);
            throw new AssertionError("shutdown returned with two jobs still queued for the module");
        } catch (TimeoutException expected) {
            // The module is still needed by both jobs, even though neither has started yet.
        }
        for (Runnable task : queued) Thread.startVirtualThread(task);
        shutdown.get(5, TimeUnit.SECONDS);
        if (!first.isDone() || !second.isDone()) throw new AssertionError("a job survived shutdown");
        if (!fighter.guard(Fighter.Options.DEFAULT).isCompletedExceptionally()) {
            throw new AssertionError("shutdown accepted another job");
        }

        CountDownLatch entered = new CountDownLatch(1);
        Movement waiting = (Movement) Proxy.newProxyInstance(Movement.class.getClassLoader(),
                new Class<?>[]{Movement.class}, (p, m, a) -> {
                    if (m.getName().equals("available")) {
                        entered.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException stop) {
                            Thread.currentThread().interrupt();
                        }
                        return false;
                    }
                    throw new AssertionError("unexpected call " + m);
                });
        Fighter active = fighter(waiting, task -> Thread.startVirtualThread(task));
        CompletableFuture<?> running = active.guard(Fighter.Options.DEFAULT);
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("job never started");
        CompletableFuture.runAsync(active::shutdown).get(5, TimeUnit.SECONDS);
        if (!running.isDone()) throw new AssertionError("active job survived shutdown");
        System.out.println("FighterShutdownTest: queued and active jobs stopped before unload");
    }

    private static Fighter fighter(Movement movement, java.util.concurrent.Executor executor) {
        return new Fighter(movement, unavailable(Navigator.class), null, null, null,
                null, null, null, null, executor, LoggerFactory.getLogger(FighterShutdownTest.class));
    }

    private static <T> T unavailable(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("available")) return false;
            throw new AssertionError("unexpected call " + m);
        }));
    }
}
