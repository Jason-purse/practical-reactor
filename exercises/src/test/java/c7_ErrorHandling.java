import org.junit.jupiter.api.*;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * It's time introduce some resiliency by recovering from unexpected events!
 * <p>
 * Read first:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#which.errors
 * https://projectreactor.io/docs/core/release/reference/#error.handling
 * <p>
 * Useful documentation:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#which-operator
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html
 *
 * @author Stefan Dragisic
 */
public class c7_ErrorHandling extends ErrorHandlingBase {

    /**
     * You are monitoring hearth beat signal from space probe. Heart beat is sent every 1 second.
     * Raise error if probe does not any emit heart beat signal longer then 3 seconds.
     * If error happens, save it in `errorRef`.
     */
    @Test
    public void houston_we_have_a_problem() {
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Flux<String> heartBeat = probeHeartBeatSignal()
                .timeout(Duration.ofSeconds(3))
                .doOnError(errorRef::set);

        StepVerifier.create(heartBeat)
                .expectNextCount(3)
                .expectError(TimeoutException.class)
                .verify();

        Assertions.assertTrue(errorRef.get() instanceof TimeoutException);
    }

    /**
     * Retrieve currently logged user.
     * If any error occurs, exception should be further propagated as `SecurityException`.
     * Keep original cause.
     */
    @Test
    public void potato_potato() {
        Mono<String> currentUser = getCurrentUser()
//                .onErrorResume(e -> Mono.error(new SecurityException(e)))
                .onErrorMap(SecurityException::new)
                //use SecurityException
                ;

        StepVerifier.create(currentUser)
                .expectErrorMatches(e -> e instanceof SecurityException &&
                        e.getCause().getMessage().equals("No active session, user not found!"))
                .verify();
    }

    /**
     * Consume all the messages `messageNode()`.
     * Ignore any failures, and if error happens finish consuming silently without propagating any error.
     */
    @Test
    public void under_the_rug() {
        Flux<String> messages = messageNode()
                .onErrorResume(t -> Flux.empty());

        StepVerifier.create(messages)
                .expectNext("0x1", "0x2")
                .verifyComplete();
    }

    /**
     * Retrieve all the messages `messageNode()`,and if node suddenly fails
     * use `backupMessageNode()` to consume the rest of the messages.
     */
    @Test
    public void have_a_backup() {
        //todo: feel free to change code as you need
        Flux<String> messages = messageNode()
                .onErrorResume(t -> backupMessageNode());

        //don't change below this line
        StepVerifier.create(messages)
                .expectNext("0x1", "0x2", "0x3", "0x4")
                .verifyComplete();
    }

    /**
     * Consume all the messages `messageNode()`, if node suddenly fails report error to `errorReportService` then
     * propagate error downstream.
     */
    @Test
    public void error_reporter() {
        //todo: feel free to change code as you need
        Flux<String> messages = messageNode()
                .onErrorResume(e -> errorReportService(e).then(Mono.error(e)));

        //don't change below this line
        StepVerifier.create(messages)
                .expectNext("0x1", "0x2")
                .expectError(RuntimeException.class)
                .verify();
        Assertions.assertTrue(errorReported.get());
    }

    /**
     * Execute all tasks from `taskQueue()`. If task executes
     * without any error, commit task changes, otherwise rollback task changes.
     * Do don't propagate any error downstream.
     */
    @Test
    public void unit_of_work() {
        Flux<Task> taskFlux = taskQueue()
                .flatMap(task -> task.execute()
                        // 出现问题... 则 回滚
                        .onErrorResume(t -> task.rollback(t).then(Mono.error(t)))
                        // 没有问题,则提交
                        .then(task.commit())

                        // 出现问题, 都直接empty() swallow
                        .onErrorResume(e -> Mono.empty())
                        // 返回任务
                        .thenReturn(task)
                );

        // 任务flux ..
        StepVerifier.create(taskFlux)
                .expectNextMatches(task -> task.executedExceptionally.get())
                .expectNextMatches(task -> task.executedSuccessfully.get())
                .verifyComplete();
    }

    /**
     * `getFilesContent()` should return files content from three different files. But one of the files may be
     * corrupted and will throw an exception if opened.
     * Using `onErrorContinue()` skip corrupted file and get the content of the other files.
     */
    @Test
    public void billion_dollar_mistake() {
        Flux<String> content = getFilesContent()
                .flatMap(Function.identity())
                .onErrorContinue((e, value) -> System.out.println("异常: " + e.getMessage()));

        StepVerifier.create(content)
                .expectNext("file1.txt content", "file3.txt content")
                .verifyComplete();
    }

    /**
     * Quote from one of creators of Reactor: onErrorContinue is my billion-dollar mistake. `onErrorContinue` is
     * considered as a bad practice, its unsafe and should be avoided.
     * <p>
     * {@see <a href="https://nurkiewicz.com/2021/08/onerrorcontinue-reactor.html">onErrorContinue</a>}
     * <p>
     * 上述文章的阅读结果就感觉是 onErrorContinue 可能会误导人像 边缘操作符那样编程,但是它既然是非边缘操作符, 肯定不是功能叠加的 ..
     * <p>
     * 多个onErrorContinue 不可能叠加在同一个流上实现错误处理 .
     * <p>
     * 但是实际开发中应该可以避免onErrorContinue的使用 .. 因为它可以通过onErrorResume / onErrorReturn 得到相同的回应 ..
     * <p>
     * 更多的是教会你如何使用边缘操作符 以及onErrorResume  /return 其他错误处理的方式来消除 continue 操作符的歧义 ..
     * <p>
     * {@see <a href="https://devdojo.com/ketonemaniac/reactor-onerrorcontinue-vs-onerrorresume">onErrorContinue vs onErrorResume</a>}
     * {@see <a href="https://bsideup.github.io/posts/daily_reactive/where_is_my_exception/">Where is my exception?</a>}
     * <p>
     * Your task is to implement `onErrorContinue()` behaviour using `onErrorResume()` operator,
     * by using knowledge gained from previous lessons.
     */
    @Test
    public void resilience() {
        //todo: change code as you need
        Flux<String> content = getFilesContent()
                // 通过将元素的错误直接在内部扼杀
                .flatMap(e -> e.onErrorResume(it -> Mono.empty())); //start from here

        //don't change below this line
        StepVerifier.create(content)
                .expectNext("file1.txt content", "file3.txt content")
                .verifyComplete();
    }


    @Test
    public void onErrorContinue() {
        Flux.range(1, 5)
                .doOnNext(i -> System.out.println("input=" + i))
                .flatMap(i -> Mono.just(i)
                        .map(j -> j == 2 ? j / 0 : j)
                        .map(j -> j * 2)
                        .onErrorResume(err -> {
                            System.out.println("onErrorResume");
                            return Mono.empty();
                        })
                        // 停止下游的onErrorContinue 对 onErrorResume的干扰
                        .onErrorStop()
                )
                .onErrorContinue((err, i) -> {
                    System.out.println("onErrorContinue=" + i);
                })
                .reduce((i, j) -> i + j)
                .doOnNext(i -> System.out.println("sum=" + i))
                .block();
    }


    /**
     * 下面这段代码  多次重复的OnErrorContinue导致,第二个失效 ..
     * <p>
     * 生产环境下,最好别这样用,也就是不要在代码中充斥 onErrorContinue ..
     */
    @Test
    public void continueHandle() {
        Flux
                .just("one.txt", "two.txt", "three.txt")
                .flatMap(file -> Mono.fromCallable(() -> new File("/dev", file).createNewFile()))
                .doOnNext(e -> System.out.println("got file " + e))
                .onErrorContinue(FileNotFoundException.class, (ex, o) -> System.out.println("Not found? " + ex.toString()))
                .onErrorContinue(IOException.class, (ex, o) -> System.out.println("I/O error " + ex.toString()))
                .subscribe(t -> {
                }, System.out::println);
    }


    /**
     * 这里说明了,即使存在onErrorContinue 或者 onErrorResume 但是不可能解决所有问题
     * <p>
     * 没有反压, 流也有可能失控 ..
     */
    @Test
    public void onErrorCannotResolveAllProblem() {
        Flux.interval(Duration.ofMillis(100))
                // 下游处理太慢了,反压
                .onBackpressureDrop()
                .log()
                .concatMap(i -> Mono.just(i)
                        .delayElement(Duration.ofSeconds(1))
                        .then(Mono.error(new RuntimeException("DIE!!! " + i)))
                )
                .onErrorContinue((e, o) -> {
                    System.out.println("No worries, I have you covered!" + e.getMessage());
                })
                .blockLast();
    }

    /**
     * You are trying to read temperature from your recently installed DIY IoT temperature sensor. Unfortunately, sensor
     * is cheaply made and may not return value on each read. Keep retrying until you get a valid value.
     */
    @Test
    public void its_hot_in_here() {
        Mono<Integer> temperature = temperatureSensor()
                .retry();

//        temperature.subscribe(System.out::println);

        StepVerifier.create(temperature)
                .expectNext(34)
                .verifyComplete();
    }

    /**
     * In following example you are trying to establish connection to database, which is very expensive operation.
     * You may retry to establish connection maximum of three times, so do it wisely!
     * FIY: database is temporarily down, and it will be up in few seconds (5).
     */
    @Test
    public void back_off() {
        Mono<String> connection_result = establishConnection()
                .retryWhen(Retry.fixedDelay(3,Duration.ofSeconds(5)))
                ;

        StepVerifier.create(connection_result)
                .expectNext("connection_established")
                .verifyComplete();
    }

    /**
     * You are working with legacy system in which you need to read alerts by pooling SQL table. Implement polling
     * mechanism by invoking `nodeAlerts()` repeatedly until you get all (2) alerts. If you get empty result, delay next
     * polling invocation by 1 second.
     */
    @Test
    public void good_old_polling() {
        //todo: change code as you need
        Flux<String> alerts = nodeAlerts()
                // 当为空的时候重复 ..
                // 然后 根据伴生对象的延时实现下一次的重复 ..
                .repeatWhenEmpty(it -> {
                    return it.delayElements(Duration.ofSeconds(1));
                })
                .repeat()
                .take(2);

        //don't change below this line
        StepVerifier.create(alerts.take(2))
                .expectNext("node1:low_disk_space", "node1:down")
                .verifyComplete();
    }

    @Test
    public void emptyElements() {
        nodeAlerts().repeat().take(5).subscribe(e -> {
            System.out.println("value = " + e);
        });
    }

    public static class SecurityException extends Exception {

        public SecurityException(Throwable cause) {
            super(cause);
        }
    }
}
