import org.junit.jupiter.api.*;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.StepVerifierOptions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backpressure is a mechanism that allows a consumer to signal to a producer that it is ready receive data.
 * This is important because the producer may be sending data faster than the consumer can process it, and can overwhelm consumer.
 * <p>
 * Read first:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#reactive.backpressure
 * https://projectreactor.io/docs/core/release/reference/#_on_backpressure_and_ways_to_reshape_requests
 * https://projectreactor.io/docs/core/release/reference/#_operators_that_change_the_demand_from_downstream
 * https://projectreactor.io/docs/core/release/reference/#producing
 * https://projectreactor.io/docs/core/release/reference/#_asynchronous_but_single_threaded_push
 * https://projectreactor.io/docs/core/release/reference/#_a_hybrid_pushpull_model
 * https://projectreactor.io/docs/core/release/reference/#_an_alternative_to_lambdas_basesubscriber
 * <p>
 * Useful documentation:
 * <p>
 * https://projectreactor.io/docs/core/release/reference/#which-operator
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html
 *
 * @author Stefan Dragisic
 */
public class c10_Backpressure extends BackpressureBase {

    /**
     * In this exercise subscriber (test) will request several messages from the message stream.
     * Hook to the requests and record them to the `requests` list.
     */
    @Test
    public void request_and_demand() {
        CopyOnWriteArrayList<Long> requests = new CopyOnWriteArrayList<>();
        Flux<String> messageStream = messageStream1()
                .doOnRequest(requests::add);

        // 这种操作 ...
        StepVerifier.create(messageStream, StepVerifierOptions.create().initialRequest(0))
                .expectSubscription()
                .thenRequest(1)
                .then(() -> pub1.next("msg#1"))
                .thenRequest(3)
                .then(() -> pub1.next("msg#2", "msg#3"))
                .then(pub1::complete)
                .expectNext("msg#1", "msg#2", "msg#3")
                .verifyComplete();

        Assertions.assertEquals(List.of(1L, 3L), requests);
    }

    /**
     * Adjust previous solution in such a way that you limit rate of requests. Number of requested messages stays the
     * same, but each request should be limited to 1 message.
     */
    @Test
    public void limited_demand() {
        CopyOnWriteArrayList<Long> requests = new CopyOnWriteArrayList<>();
        Flux<String> messageStream = messageStream2()
                .doOnRequest(requests::add)
                .limitRate(1);

        StepVerifier.create(messageStream, StepVerifierOptions.create().initialRequest(0))
                .expectSubscription()
                // 第一次 1(pull)
                .thenRequest(1)
                .then(() -> pub2.next("msg#1"))
                // 然后请求三次,然后通过 75%的对于总频率的计算 1 * .075 约等于 1
                // 的尝试再次向上游请求一个（然后由于我们的缓冲区容量频率限制为 1)
                // 所以只要消费1个,预抓取一个 ..
                // 也就是下游虽然请求3个,除了第一次拉1,之外后续依旧是 1
                .thenRequest(3)
                .then(() -> pub2.next("msg#2", "msg#3"))
                .then(pub2::complete)
                .expectNext("msg#1", "msg#2", "msg#3")
                .verifyComplete();

        Assertions.assertEquals(List.of(1L, 1L, 1L, 1L), requests);
    }

    /**
     * Finish the implementation of the `uuidGenerator` so it exactly requested amount of UUIDs. Or better said, it
     * should respect the backpressure of the consumer.
     */
    @Test
    public void uuid_generator() {
        Flux<UUID> uuidGenerator = Flux.create(sink -> {
//            while(!sink.isCancelled()) {
//                // 尝试判断 ..
//                long l = sink.requestedFromDownstream();
//                if(l > 0) {
//                    for (long i = 0; i < l; i++) {
//                        sink.next(UUID.randomUUID());
//                    }
//                }
//            }
            sink.onRequest(n -> {
                for (long i = 0; i < n; i++) {
                    sink.next(UUID.randomUUID());
                }
            });
        });

        StepVerifier.create(uuidGenerator
                                .doOnNext(System.out::println)
                                .timeout(Duration.ofSeconds(1))
                                .onErrorResume(TimeoutException.class, e -> Flux.empty()),
                        StepVerifierOptions.create().initialRequest(0))
                .expectSubscription()
                .thenRequest(10)
                .expectNextCount(10)
                .thenCancel()
                .verify();
    }

    /**
     * You are receiving messages from malformed publisher that may not respect backpressure.
     * In case that publisher produces more messages than subscriber is able to consume, raise an error.
     */
    @Test
    public void pressure_is_too_much() {
        Flux<String> messageStream = messageStream3()
                .onBackpressureError();

        StepVerifier.create(messageStream, StepVerifierOptions.create()
                        .initialRequest(0))
                .expectSubscription()
                .thenRequest(3)
                .then(() -> pub3.next("A", "B", "C", "D"))
                .expectNext("A", "B", "C")
                // 这里不关心反压 .. 所以 抛出了一个overflow ..
                .expectErrorMatches(Exceptions::isOverflow)
                .verify();
    }

    /**
     * You are receiving messages from malformed publisher that may not respect backpressure. In case that publisher
     * produces more messages than subscriber is able to consume, buffer them for later consumption without raising an
     * error.
     */
    @Test
    public void u_wont_brake_me() {
        Flux<String> messageStream = messageStream4()
                .onBackpressureBuffer();

        // 可以这样调试
        // 先给出验证器选项 初始化请求为 0
        StepVerifier.create(messageStream, StepVerifierOptions.create()
                        .initialRequest(0))
                .expectSubscription()
                .thenRequest(3)
                .then(() -> pub4.next("A", "B", "C", "D"))
                .expectNext("A", "B", "C")
                .then(() -> pub4.complete())
                .thenAwait()
                .thenRequest(1)
                .expectNext("D")
                .verifyComplete();
    }

    /**
     * We saw how to react to request demand from producer side. In this part we are going to control demand from
     * consumer side by implementing BaseSubscriber directly.
     * Finish implementation of base subscriber (consumer of messages) with following objectives:
     * - once there is subscription, you should request exactly 10 messages from publisher
     * - once you received 10 messages, you should cancel any further requests from publisher.
     * Producer respects backpressure.
     */
    @Test
    public void subscriber() throws InterruptedException {
        AtomicReference<CountDownLatch> lockRef = new AtomicReference<>(new CountDownLatch(1));
        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<Subscription> sub = new AtomicReference<>();

        // 这里的生产者自然关心消费者反压
        remoteMessageProducer()
                .doOnCancel(() -> lockRef.get().countDown())
                .subscribeWith(new BaseSubscriber<String>() {

                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        sub.set(subscription);
                        subscription.request(10);
                    }

                    @Override
                    protected void hookOnNext(String s) {
                        System.out.println(s);
                        count.incrementAndGet();

                        if (count.compareAndSet(10, 10)) {
                            // 取消
                            cancel();
                        }
                    }
                    //-----------------------------------------------------
                });

        lockRef.get().await();
        Assertions.assertEquals(10, count.get());
    }
}
