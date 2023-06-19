import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import reactor.blockhound.BlockHound;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.NonBlocking;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * With multi-core architectures being a commodity nowadays, being able to easily parallelize work is important.
 * Reactor helps with that by providing many mechanisms to execute work in parallel.
 * <p>
 * Read first:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#schedulers
 * https://projectreactor.io/docs/core/release/reference/#advanced-parallelizing-parralelflux
 * https://projectreactor.io/docs/core/release/reference/#_the_publishon_method
 * https://projectreactor.io/docs/core/release/reference/#_the_subscribeon_method
 * https://projectreactor.io/docs/core/release/reference/#which.time
 * <p>
 * Useful documentation:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#which-operator
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html
 *
 * @author Stefan Dragisic
 */
public class c9_ExecutionControl extends ExecutionControlBase {

    /**
     * You are working on smartphone app and this part of code should show user his notifications. Since there could be
     * multiple notifications, for better UX you want to slow down appearance between notifications by 1 second.
     * Pay attention to threading, compare what code prints out before and after solution. Explain why?
     */
    @Test
    public void slow_down_there_buckaroo() {
        long threadId = Thread.currentThread().getId();
        Flux<String> notifications = readNotifications()
                .doOnNext(System.out::println)
                // 每一个元素之间延迟一秒
                // 会将延迟的过程交给 parallel 调度器
                .delayElements(Duration.ofSeconds(1));

        StepVerifier.create(notifications
                        .doOnNext(s -> assertThread(threadId))
                )
                .expectNextCount(5)
                .verifyComplete();
    }

    private void assertThread(long invokerThreadId) {
        long currentThread = Thread.currentThread().getId();
        if (currentThread != invokerThreadId) {
            System.out.println("-> Not on the same thread");
        } else {
            System.out.println("-> On the same thread");
        }
        Assertions.assertTrue(currentThread != invokerThreadId, "Expected to be on a different thread");
    }

    /**
     * You are using free access to remote hosting machine. You want to execute 3 tasks on this machine, but machine
     * will allow you to execute one task at a time on a given schedule which is orchestrated by the semaphore. If you
     * disrespect schedule, your access will be blocked.
     * Delay execution of tasks until semaphore signals you that you can execute the task.
     */
    @Test
    public void ready_set_go() {
        //todo: feel free to change code as you need

        Flux<String> tasks = tasks()
                //
                .concatMap(task -> {
                    // 还可以另一种方式,延迟订阅到 信号发送 ..
//                    return semaphore().take(1).then(task);
                    return task.delaySubscription(semaphore());
                });

        //don't change code below
        StepVerifier.create(tasks)
                .expectNext("1")
                .expectNoEvent(Duration.ofMillis(2000))
                .expectNext("2")
                .expectNoEvent(Duration.ofMillis(2000))
                .expectNext("3")
                .verifyComplete();
    }

    /**
     * Make task run on thread suited for short, non-blocking, parallelized work.
     * Answer:
     * - Which types of schedulers Reactor provides?
     * - What is their purpose?
     * - What is their difference?
     */
    @Test
    public void non_blocking() {
        Mono<Void> task = Mono.fromRunnable(() -> {
                    Thread currentThread = Thread.currentThread();
                    assert NonBlocking.class.isAssignableFrom(Thread.currentThread().getClass());
                    System.out.println("Task executing on: " + currentThread.getName());
                    // single / parallel 是非阻塞的 。。
                }).publishOn(Schedulers.single())
                .then();

        StepVerifier.create(task)
                .verifyComplete();
    }

    /**
     * Make task run on thread suited for long, blocking, parallelized work.
     * Answer:
     * - What BlockHound for?
     */
    @Test
    public void blocking() {
        BlockHound.install(); //don't change this line

        Mono<Void> task = Mono.fromRunnable(ExecutionControlBase::blockingCall)
//                .subscribeOn(Schedulers.immediate())//todo: change this line only
                // 这里其实要考的是 线程能否被阻塞(也就是是否具有NonBlocking 接口)

                // 此线程池  基于 executorService的SchedulerWorker 来处理的 .
                // 每一个worker 它可能被底层的线程所支持

                // 那么底层的线程可能会支持多个worker ..
                // 那么此调度器的说明如下:
                //common boundedElastic 是一个调度器（Scheduler）实例，它基于 ExecutorService 的 Worker 动态创建有限数量的线程，一旦这些 Worker 被关闭，它们可以被重用。如果后台的守护线程在空闲超过 60 秒后，它们可以被回收。
                //
                //创建的线程数量的上限由一个限制值控制（默认值为可用 CPU 核心数的十倍，参见 DEFAULT_BOUNDED_ELASTIC_SIZE）。在每个支持线程上可以排队和延迟的任务提交数量也是有限的（默认值为额外的 100,000 个任务，参见 DEFAULT_BOUNDED_ELASTIC_QUEUESIZE）。超过这个限制，将会抛出 RejectedExecutionException 异常。
                //
                //为了选择线程来支持新的 Scheduler.Worker，优先选择空闲池中的线程，如果没有则创建新的线程，或者从繁忙池中复用线程。在后一种情况下，会尽力选择支持的 Worker 数量最少的线程。
                //
                //需要注意的是，如果一个线程支持的 Worker 数量较少，但这些 Worker 提交了大量的挂起任务，那么另一个 Worker 可能会被同一个线程支持，并且会看到任务被拒绝的情况。选择支持线程的策略在 Worker 创建时进行一次，并且保持不变，因此，即使在此期间有另一个线程空闲，由于两个 Worker 共享同一个线程并提交长时间运行的任务，可能会导致任务延迟。
                //
                //common boundedElastic 调度器实例只会在首次调用时创建，并被缓存起来。在后续的调用中，将会返回同一个实例，直到它被销毁。
                //
                //请注意，不能直接销毁这些共享实例，因为它们被缓存并在调用者之间共享。但是可以同时关闭它们，或者通过更改 Factory 来替换它们。

                // 由于任务最大调度数量是以线程为单位,并且当worker一旦创建,那么它的背后线程的支持策略是不变的
                // 也就是会出现 如果一个worker 提交了大量的任务,另一个worker 同样被相同线程支持,然后可能会看到任务拒绝 .
                // 并且就算另一个底层的支持线程空闲了,由于支持策略不变,那么也不一定能够调度到对应的空闲支持线程上
                .subscribeOn(Schedulers.boundedElastic())
                .then();

        StepVerifier.create(task)
                .verifyComplete();
    }

    /**
     * Adapt code so tasks are executed in parallel, with max concurrency of 3.
     */
    @Test
    public void free_runners() {
        //todo: feel free to change code as you need
        Mono<Void> task = Mono.fromRunnable(ExecutionControlBase::blockingCall);

        Flux<Void> taskQueue = Flux.just(task, task, task)
                .parallel(3)
                .runOn(Schedulers.parallel())
                .concatMap(Function.identity())
                .sequential();

        //don't change code below
        Duration duration = StepVerifier.create(taskQueue)
                .expectComplete()
                .verify();

        Assertions.assertTrue(duration.getSeconds() <= 2, "Expected to complete in less than 2 seconds");
    }

    /**
     * Adapt the code so tasks are executed in parallel, but task results should preserve order in which they are invoked.
     */
    @Test
    public void sequential_free_runners() {
        //todo: feel free to change code as you need
        Flux<String> tasks = tasks()
                // concatMap 有一个缺陷, 就是 会等待上一个执行完毕之后,才会执行下一个 ..
                .flatMapSequential(Function.identity())
        ;

        //don't change code below
        Duration duration = StepVerifier.create(tasks)
                .expectNext("1")
                .expectNext("2")
                .expectNext("3")
                .verifyComplete();

        Assertions.assertTrue(duration.getSeconds() <= 1, "Expected to complete in less than 1 seconds");
    }

    /**
     * Make use of ParallelFlux to branch out processing of events in such way that:
     * - filtering events that have metadata, printing out metadata, and mapping to json can be done in parallel.
     * Then branch in before appending events to store. `appendToStore` must be invoked sequentially!
     */
    @Test
    public void event_processor() {
        //todo: feel free to change code as you need
        Flux<String> eventStream = eventProcessor()
                .parallel()
                .runOn(Schedulers.parallel())
                .filter(event -> event.metaData.length() > 0)
                .doOnNext(event -> System.out.println("Mapping event: " + event.metaData))
                .map(this::toJson)
                .sequential()
                .concatMap(n -> appendToStore(n).thenReturn(n),250);

        //don't change code below
        StepVerifier.create(eventStream)
                .expectNextCount(250)
                .verifyComplete();

        List<String> steps = Scannable.from(eventStream)
                .parents()
                .map(Object::toString)
                .collect(Collectors.toList());

        String last = Scannable.from(eventStream)
                .steps()
                .collect(Collectors.toCollection(LinkedList::new))
                .getLast();

        Assertions.assertEquals("concatMap", last);
        Assertions.assertTrue(steps.contains("ParallelMap"), "Map operator not executed in parallel");
        Assertions.assertTrue(steps.contains("ParallelPeek"), "doOnNext operator not executed in parallel");
        Assertions.assertTrue(steps.contains("ParallelFilter"), "filter operator not executed in parallel");
        Assertions.assertTrue(steps.contains("ParallelRunOn"), "runOn operator not used");
    }

    private String toJson(Event n) {
        try {
            return new ObjectMapper().writeValueAsString(n);
        } catch (JsonProcessingException e) {
            throw Exceptions.propagate(e);
        }
    }
}
