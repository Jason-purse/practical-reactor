import org.junit.jupiter.api.*;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * In Reactor a Sink allows safe manual triggering of signals. We will learn more about multicasting and backpressure in
 * the next chapters.
 * <p>
 * Read first:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#sinks
 * https://projectreactor.io/docs/core/release/reference/#processor-overview
 * <p>
 * Useful documentation:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#which-operator
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html
 *
 * @author Stefan Dragisic
 */
public class c8_Sinks extends SinksBase {

    /**
     * You need to execute operation that is submitted to legacy system which does not support Reactive API. You want to
     * avoid blocking and let subscribers subscribe to `operationCompleted` Mono, that will emit `true` once submitted
     * operation is executed by legacy system.
     */
    @Test
    public void single_shooter() {
        //todo: feel free to change code as you need
//        Mono<Boolean> operationCompleted = Mono.create(sink -> {
//            submitOperation(() -> {
//                sink.success(true);
//                doSomeWork(); //don't change this line
//            });
//        });

        Sinks.One<Boolean> sink = Sinks.one();
        Mono<Boolean> operationCompleted = sink.asMono();
        submitOperation(() -> {
            doSomeWork(); //don't change this line
            sink.tryEmitValue(true);
        });

        //don't change code below
        StepVerifier.create(operationCompleted.timeout(Duration.ofMillis(5500)))
                .expectNext(true)
                .verifyComplete();
    }

    /**
     * Similar to previous exercise, you need to execute operation that is submitted to legacy system which does not
     * support Reactive API. This time you need to obtain result of `get_measures_reading()` and emit it to subscriber.
     * If measurements arrive before subscribers subscribe to `get_measures_readings()`, buffer them and emit them to
     * subscribers once they are subscribed.
     */
    @Test
    public void single_subscriber() {
        //todo: feel free to change code as you need
//        Flux<Integer> measurements = Flux.<Integer>push(sink -> {
//                    submitOperation(() -> {
//
//                        List<Integer> measures_readings = get_measures_readings(); //don't change this line
//                        for (Integer measuresReading : measures_readings) {
//                            sink.next(measuresReading);
//                        }
//
//                        sink.complete();
//                    });
//                })
//                .onBackpressureBuffer();
//

        Sinks.Many<Integer> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<Integer> measurements = sink.asFlux();
        submitOperation(() -> {
            List<Integer> measures_readings = get_measures_readings(); //don't change this line
            measures_readings.forEach(sink::tryEmitNext);
            sink.tryEmitComplete();
        });



        // 通过虚拟时间来快速 测试
        //don't change code below
        StepVerifier.withVirtualTime(() -> measurements.delaySubscription(Duration.ofSeconds(6)))
                .thenAwait(Duration.ofSeconds(6))
                .expectNext(0x0800, 0x0B64, 0x0504)
                .verifyComplete();
    }

    /**
     * Same as previous exercise, but with twist that you need to emit measurements to multiple subscribers.
     * Subscribers should receive only the signals pushed through the sink after they have subscribed.
     */
    @Test
    public void it_gets_crowded() {
        //todo: feel free to change code as you need
//        Flux<Integer> measurements = Flux.<Integer>push(sink -> {
//            submitOperation(() -> {
//                List<Integer> measures_readings = get_measures_readings(); //don't change this line
//                for (Integer measuresReading : measures_readings) {
//                    sink.next(measuresReading);
//                }
//                sink.complete();
//            });
//        }
        // 当然这也是可以的 ..(一直弹射,下游如果无法处理)
//        .onBackpressureDrop();


        // 上述代码存在问题(需要面向sink)
        Sinks.Many<Integer> sink = Sinks.many().multicast().onBackpressureBuffer();
        Flux<Integer> measurements = sink.asFlux();
        submitOperation(() -> {
            List<Integer> measures_readings = get_measures_readings(); //don't change this line
            // 不一定能够弹射 .
            measures_readings.forEach(sink::tryEmitNext);
            // 不一定能够完成 ..
            // 查看javadoc 了解对各种结果的处理 ..
            sink.tryEmitComplete();
        });


        //don't change code below
        StepVerifier.withVirtualTime(() -> Flux.merge(measurements
                                .delaySubscription(Duration.ofSeconds(1)),
                        measurements.ignoreElements()))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(0x0800, 0x0B64, 0x0504)
                .verifyComplete();
    }

    /**
     * By default, if all subscribers have cancelled (which basically means they have all un-subscribed), sink clears
     * its internal buffer and stops accepting new subscribers. For this exercise, you need to make sure that if all
     * subscribers have cancelled, the sink will still accept new subscribers. Change this behavior by setting the
     * `autoCancel` parameter.
     */
    @Test
    public void open_24_7() throws InterruptedException {
        //todo: set autoCancel parameter to prevent sink from closing
        Sinks.Many<Integer> sink = Sinks.many().multicast().onBackpressureBuffer(Integer.MAX_VALUE,false);
        Flux<Integer> flux = sink.asFlux();

        //don't change code below
        submitOperation(() -> {
            get_measures_readings().forEach(sink::tryEmitNext);
            submitOperation(sink::tryEmitComplete);
        });

        //subscriber1 subscribes, takes one element and cancels
        StepVerifier sub1 = StepVerifier.create(Flux.merge(flux.take(1)))
                .expectNext(0x0800)
                .expectComplete()
                .verifyLater();

        //subscriber2 subscribes, takes one element and cancels
        // 这里的flux.take(..) 表示消费的进度
        // 取长桶(桶的消费最长位置)
        // 由于它是多播的,并且不可回放
        // 所以 take(1) 表示仅仅消费了1个 ..
        // 这两个订阅者基本上是同一时间上订阅的 ..
        StepVerifier sub2 = StepVerifier.create(Flux.merge(flux.take(1)))
                .expectNext(0x0800)
                .expectComplete()
                .verifyLater();


        //subscriber3 subscribes after all previous subscribers have cancelled
        // 取消之后重新订阅,之前的流已经被消费一个元素 .
        // 此时应该注意到,它应该估计只能消费后续元素 ..
        StepVerifier sub3 = StepVerifier.withVirtualTime(() -> flux.take(3)
                        .delaySubscription(Duration.ofSeconds(6)))
                .thenAwait(Duration.ofSeconds(6))
                .expectNext(0x0B64) //first measurement `0x0800` was already consumed by previous subscribers
                .expectNext(0x0504)
                .expectComplete()
                .verifyLater();

        sub1.verify();
        sub2.verify();
        sub3.verify();

        System.out.println("重新订阅");
        // 此时由于 订阅时间晚于 sub3(也就是sub3 订阅完毕也已经取消)
        // sink中的缓冲区为空,没有可以接受的数据 ..
        Flux.merge(flux.take(3)).subscribe(System.out::println);

        Thread.sleep(3000);
    }

    /**
     * If you look closely, in previous exercises third subscriber was able to receive only two out of three
     * measurements. That's because used sink didn't remember history to re-emit all elements to new subscriber.
     * Modify solution from `open_24_7` so third subscriber will receive all measurements.
     */
    @Test
    public void blue_jeans() throws InterruptedException {
//        Sinks.Many<Integer> sink = Sinks.many().multicast().onBackpressureBuffer();
//        Flux<Integer> flux = sink.asFlux().replay().autoConnect();

        // 下面这一种也可以
        Sinks.Many<Integer> sink = Sinks.many().replay().all();
        Flux<Integer> flux = sink.asFlux();

//don't change code below
        submitOperation(() -> {
            get_measures_readings().forEach(sink::tryEmitNext);
            submitOperation(sink::tryEmitComplete);
        });

        //subscriber1 subscribes, takes one element and cancels
        StepVerifier sub1 = StepVerifier.create(Flux.merge(flux.take(1)))
                .expectNext(0x0800)
                .expectComplete()
                .verifyLater();

        //subscriber2 subscribes, takes one element and cancels
        StepVerifier sub2 = StepVerifier.create(Flux.merge(flux.take(1)))
                .expectNext(0x0800)
                .expectComplete()
                .verifyLater();

        //subscriber3 subscribes after all previous subscribers have cancelled
        StepVerifier sub3 = StepVerifier.create(flux.take(3)
                        .delaySubscription(Duration.ofSeconds(6)))
                .expectNext(0x0800)
                .expectNext(0x0B64)
                .expectNext(0x0504)
                .expectComplete()
                .verifyLater();

        sub1.verify();
        sub2.verify();
        sub3.verify();
    }


    /**
     * There is a bug in the code below. May multiple producer threads concurrently generate data on the sink?
     * If yes, how? Find out and fix it.
     */
    @Test
    public void emit_failure() {
        //todo: feel free to change code as you need
//        Sinks.Many<Integer> sink = Sinks.unsafe().many().replay().all();
//        for (int i = 1; i <= 50; i++) {
//            int finalI = i;
//            new Thread(() -> {
//                // 加锁即可 ...
//                synchronized (c8_Sinks.class) {
//                    sink.tryEmitNext(finalI);
//                }
//            }).start();
//        }

        // 或者专门处理 串行化处理 ..
        // 这个示例中, 仅仅这一个订阅者 ..
        // 用什么Sinks 都可以 ..
        Sinks.Many<Integer> sink = Sinks.many().multicast().onBackpressureBuffer();

        for (int i = 1; i <= 50; i++) {
            int finalI = i;
            // 处理串行化错误处理 ..
            new Thread(() -> sink.emitNext(finalI, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(250)))).start();
        }


        //don't change code below
        StepVerifier.create(sink.asFlux()
                        .doOnNext(System.out::println)
                        .take(50))
                .expectNextCount(50)
                .verifyComplete();
    }
}
