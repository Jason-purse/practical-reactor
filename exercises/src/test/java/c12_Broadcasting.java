import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In this chapter we will learn difference between hot and cold publishers,
 * how to split a publisher into multiple and how to keep history so late subscriber don't miss any updates.
 *
 * Read first:
 *
 * https://projectreactor.io/docs/core/release/reference/#reactor.hotCold
 * https://projectreactor.io/docs/core/release/reference/#which.multicasting
 * https://projectreactor.io/docs/core/release/reference/#advanced-broadcast-multiple-subscribers-connectableflux
 *
 * Useful documentation:
 *
 * https://projectreactor.io/docs/core/release/reference/#which-operator
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html
 * https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html
 *
 * @author Stefan Dragisic
 */
public class c12_Broadcasting extends BroadcastingBase {

    /**
     * Split incoming message stream into two streams, one contain user that sent message and second that contains
     * message payload.
     */
    @Test
    public void sharing_is_caring() throws InterruptedException {
        Flux<Message> messages = messageStream()
                .share();

        //don't change code below
        Flux<String> userStream = messages.map(m -> m.user);
        Flux<String> payloadStream = messages.map(m -> m.payload);

        CopyOnWriteArrayList<String> metaData = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> payload = new CopyOnWriteArrayList<>();

        userStream.doOnNext(n -> System.out.println("User: " + n)).subscribe(metaData::add);
        payloadStream.doOnNext(n -> System.out.println("Payload: " + n)).subscribe(payload::add);

        Thread.sleep(3000);

        Assertions.assertEquals(Arrays.asList("user#0", "user#1", "user#2", "user#3", "user#4"), metaData);
        Assertions.assertEquals(Arrays.asList("payload#0", "payload#1", "payload#2", "payload#3", "payload#4"),
                                payload);
    }

    /**
     * Since two subscribers are interested in the updates, which are coming from same source, convert `updates` stream
     * to from cold to hot source.
     * Answer: What is the difference between hot and cold publisher? Why does won't .share() work in this case?
     */
    @Test
    public void hot_vs_cold() {

        // 这是一种,通过在达到多个订阅之后 然后连接到上游,但是低于这个订阅的时候 取消订阅到上游,但是在2秒中之内 保持上游不关闭 ..
        // 如果没有周期 默认是直接关闭(关闭连接), 一旦关闭连接,publish操作符的使命(转换为热流)就结束了 ..
        // 此时 再次订阅将导致一个冷流的过程继续发生 ...
//        Flux<String> updates = systemUpdates().publish().refCount(1, Duration.ofSeconds(2));
        Flux<String> updates = systemUpdates().publish().autoConnect(1);

        //subscriber 1
        StepVerifier.create(updates.take(3).doOnNext(n -> System.out.println("subscriber 1 got: " + n)))
                    .expectNext("RESTARTED", "UNHEALTHY", "HEALTHY")
                    .verifyComplete();

        //subscriber 2
        StepVerifier.create(updates.take(4).doOnNext(n -> System.out.println("subscriber 2 got: " + n)))
                    .expectNext("DISK_SPACE_LOW", "OOM_DETECTED", "CRASHED", "UNKNOWN")
                    .verifyComplete();
    }

    /**
     * In previous exercise second subscriber subscribed to update later, and it missed some updates. Adapt previous
     * solution so second subscriber will get all updates, even the one's that were broadcaster before its
     * subscription.
     */
    @Test
    public void history_lesson() {
        Flux<String> updates = systemUpdates()
                // shared
//                .share()
                // 回放是可以的 ..
//                .replay()
//                .autoConnect(1)

                // 三种方式都可以 ..
                .cache();

        //subscriber 1
        StepVerifier.create(updates.take(3).doOnNext(n -> System.out.println("subscriber 1 got: " + n)))
                    .expectNext("RESTARTED", "UNHEALTHY", "HEALTHY")
                    .verifyComplete();

        //subscriber 2
        StepVerifier.create(updates.take(5).doOnNext(n -> System.out.println("subscriber 2 got: " + n)))
                    .expectNext("RESTARTED", "UNHEALTHY", "HEALTHY", "DISK_SPACE_LOW", "OOM_DETECTED")
                    .verifyComplete();
    }

}
