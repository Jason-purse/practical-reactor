import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static reactor.core.publisher.Sinks.EmitFailureHandler.FAIL_FAST;

/**
 * @author jasonj
 * @date 2023/6/18
 * @time 17:21
 * @description
 **/
public class C12BroadCastingTests {

    @Test
    public void hostFlux() throws InterruptedException {

        Flux<Integer> map = Flux.just(1, 2, 3, 4)
                .doOnNext(e -> {
                    System.out.println("next: " + e);
                })
                .map(e -> {
                    System.out.println("value: " + e);
                    return e;
                })
                .subscribeOn(Schedulers.boundedElastic());

        map.subscribe(e -> {
            System.out.println("one: " + e);
        });

        map.subscribe(e -> {
            System.out.println("two: " + e);
        });

        Thread.sleep(4000);
    }

    @Test
    public void iterable() throws InterruptedException {
        // 冷 序列
        // hasNext 触发多次 ..
        Flux<Object> flux = Flux.fromIterable(new Iterable<Object>() {
                    @Override
                    public Iterator<Object> iterator() {

                        return new Iterator<Object>() {

                            private Iterator<Integer> iterator = Arrays.asList(1, 2, 3, 4).iterator();

                            @Override
                            public boolean hasNext() {
                                System.out.println("hasNext");
                                return iterator.hasNext();
                            }

                            @Override
                            public Object next() {
                                System.out.println("next");
                                return iterator.next();
                            }
                        };
                    }
                })
                .map(e -> {
                    System.out.println("map: " + e);
                    return e;
                }).subscribeOn(Schedulers.boundedElastic());

        flux.subscribe(e -> {
            System.out.println("one: " + e);
        });

        flux.subscribe(e -> {
            System.out.println("two: " + e);
        });

        Thread.sleep(2000);
    }

    /**
     * 根据官方的运行示例图,可以知道 热序列(在发布者方面 它仅仅执行一次,但是在订阅者方面,它是存在多个可运行的函数)
     *
     * 意味着一个发布者可能会调用多个这种可执行函数 ..
     *
     * 那么以下代码利用了 map函数的订阅者  来根据不同订阅者执行不同的 映射 ..
     *
     */
    @Test
    public void hostBySinks() {
        Sinks.Many<Object> hotSource = Sinks.unsafe().many().multicast().directBestEffort();

        Map<Object,Boolean> bucket = new HashMap<>();

        Flux<Object> hotFlux = hotSource.asFlux().map(e -> {
            Boolean aBoolean = bucket.putIfAbsent(e, Boolean.TRUE);
            if(aBoolean != null && aBoolean) {
                return "duplicate " + e;
            }
            return e;
        });

        hotFlux.subscribe(d -> System.out.println("Subscriber 1 to Hot Source: "+d));

        hotSource.emitNext("blue", FAIL_FAST);
        hotSource.tryEmitNext("green").orThrow();

        hotFlux.subscribe(d -> System.out.println("Subscriber 2 to Hot Source: "+d));

        hotSource.emitNext("orange", FAIL_FAST);
        hotSource.emitNext("purple", FAIL_FAST);
        hotSource.emitComplete(FAIL_FAST);

    }

    /**
     * 总结,在我看来,冷和热 就是有无状态的区别
     *
     * 热就是有状态,具有记忆性
     *
     * 冷就是没有记忆性
     */
    @Test
    public void coldToHot() {
        AtomicInteger count = new AtomicInteger(0);
        Flux<Integer> defer = Flux.defer(() -> {
            count.incrementAndGet();
            return Flux.fromStream(Stream.iterate(0, e -> e + 1).limit(count.get()));
        });

        // cold 多次订阅,将会导致 存在不同的流数据 ..

        defer.subscribe(e -> {
            System.out.print("cold one: " + e);
            System.out.print(" ");
        });

        System.out.println();

        defer.subscribe(e -> {
            System.out.print("cold two: " + e);
            System.out.print(" ");
        });
        System.out.println();

        defer.subscribe(e -> {
            System.out.print("cold two: " + e);
            System.out.print(" ");
        });
        System.out.println();

        // 转为热
        // 并没有重复执行 count1 的增长 ..
        AtomicInteger count1 = new AtomicInteger(1);
        Flux<Integer> defer1 = Flux.defer(() -> {
            count1.incrementAndGet();
            return Flux.fromStream(Stream.iterate(0, e -> e + 1).limit(count1.get()));
        })
                .replay().autoConnect();

        defer1.subscribe(e -> {
            System.out.println("hot one: " + e);
        });

        defer1.subscribe(e -> {
            System.out.println("hot two: " + e);
        });
    }
}
