import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.blockhound.integration.RxJava2Integration;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.*;
import reactor.core.publisher.FluxSink.OverflowStrategy;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static reactor.util.concurrent.Queues.SMALL_BUFFER_SIZE;

/**
 * @author jasonj
 * @date 2023/6/12
 * @time 17:07
 * @description 既然Flux 至少需要请求一次 .
 * <p>
 * 并且 下游对上游的请求,如果上游没有可用的元素,那么当上游一旦创建好就会立即推送,如果有直接拉取 ..
 * <p>
 * 那么如果我请求过多会怎样,然而上游没有足够多的元素 ..
 * <p>
 * 流中的元素的处理形式是?
 * <p>
 * prefetch 策略(limitRate / limitRequest)
 * <p>
 * sink的使用 ..
 * <p>
 * 与现存的异步api 桥接
 * <p>
 * 还包括一些其他测试 ..
 * <p>
 * <p>
 * 对应的学习总结参考文档如下:
 *
 *
 * <a href="https://www.processon.com/mindmap/648128d85bb13b5c487ee234">java 学习思维导图</a>
 **/
public class C1FluxIssueTests {


    /**
     * 当下游请求更多元素,然而上游没有更多的时候,此时则自动结束流 ..
     */
    @Test
    public void fluxFiveAndDownStreamNeedTen() {
        Flux.defer(() -> {
                    return Flux.fromStream(Stream.iterate(0, v -> v + 1).limit(5));
                })
                .subscribe(new BaseSubscriber<Integer>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        subscription.request(10);
                    }

                    @Override
                    protected void hookOnNext(Integer value) {
                        System.out.println(value);
                    }
                });
    }


    /**
     * 如果上游很多元素,但是下游仅仅请求一部分会怎样 ?
     * <p>
     * 那么很显然 就是一个有界请求 而已 ..
     */
    @Test
    public void fluxTenAndDownStreamNeedFive() {
        Flux.defer(() -> {
                    return Flux.fromStream(Stream.iterate(0, v -> v + 1).limit(10));
                })
                .subscribe(new BaseSubscriber<Integer>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        subscription.request(5);
                    }

                    @Override
                    protected void hookOnNext(Integer value) {
                        System.out.println(value);
                    }
                });
    }


    /**
     * 从下面的示例中,可以发现操作符对流的处理,都是单个元素单个元素处理的 ..
     * <p>
     * 并不是像 java Stream 一样 ..(整体处理) ..
     */
    @Test
    public void elementsInFluxWithHowWork() {
        Flux.just(1, 2, 3, 4, 6)
                .map(i -> {
                    System.out.println("operator");
                    return i * 2;
                })
                .subscribe(new BaseSubscriber<Integer>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        subscription.request(2);
                    }

                    @Override
                    protected void hookOnNext(Integer value) {
                        System.out.println(value);
                    }
                });
    }

    @Test
    public void prefetchWithFlux() {
        Flux.defer(() -> {
                    return Flux.fromStream(
                            Stream.iterate(0, e -> e + 1).limit(300)
                    );
                })
                .log()

                // 75% ( 10 * 0.75) 约等于 8
                .limitRate(10)
                .log()
                .subscribe(new BaseSubscriber<>() {

                    private int count = 0;

                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        subscription.request(100);
                    }

                    @Override
                    protected void hookOnNext(Integer value) {
                        count++;

                        if (count % 100 == 0) {
                            request(100);
                            System.out.printf("第%s批开始%n", count / 100);
                        }
                    }
                });
    }

    @Test
    public void prefetchWithArea() {
        Flux.defer(() -> {
                    return Flux.fromStream(
                            Stream.iterate(0, e -> e + 1).limit(300)
                    );
                })
                .log()

                // 75% ( 10 * 0.75) 约等于 8
                .limitRate(10, 5)
                .log()
                .subscribe(new BaseSubscriber<>() {

                    private int count = 0;

                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        subscription.request(100);
                    }

                    @Override
                    protected void hookOnNext(Integer value) {
                        count++;

                        if (count % 100 == 0) {
                            request(100);
                            System.out.printf("第%s批开始%n", count / 100);
                        }
                    }
                });
    }

    @Test
    public void withLimitRequestForCapped() {
        Flux.just(1, 2, 3, 4, 5, 6)
                .limitRequest(4)
                .subscribe(System.out::println);

        // 等价于
        Flux.just(1, 2, 3, 4, 5, 6)
                .take(4)
                .subscribe(System.out::println);
    }

    @Test
    public void synchronousSinkWithFlux() {
        AtomicInteger i = new AtomicInteger();
        Flux.generate(sink -> {
                    if (i.get() < 100) {
                        sink.next(i.incrementAndGet());
                    } else {
                        sink.complete();
                    }
                })
                .skip(90)
                .subscribe(System.out::println);

        // equals to

        Flux.generate(AtomicInteger::new, (state, sink) -> {
                    if (state.get() < 100) {
                        sink.next(state.incrementAndGet());
                    } else {
                        sink.complete();
                    }

                    return state;
                }).skip(90)
                .subscribe(System.out::println);


        int i1 = i.get();

        while (true) {
            int value = i.get();

            if (i.compareAndSet(value, value + 1)) {
                break;
            }
        }
    }

    @Test
    public void subscribe() {
        Flux.defer(() -> {
            return Flux.fromStream(Stream.iterate(0, i -> i + 1).limit(3600));
        }).subscribe(System.out::println);
    }

    interface MyEventListener<T> {
        void onDataChunk(List<T> chunk);

        void processComplete();
    }

    /**
     * 很奇怪,为什么subscribe 订阅之后, 立即返回了 ...
     *
     * @throws InterruptedException
     */
    @Test
    public void createForSinkWithExistingSyncAPIBridge() throws InterruptedException {

        AtomicReference<MyEventListener<String>> listenerAtomicReference = new AtomicReference<>();

        Flux<Object> flux = Flux.create(fluxSink -> {

            System.out.println("sink Thread name " + Thread.currentThread().getName());
            listenerAtomicReference.set(new MyEventListener<String>() {
                @Override
                public void onDataChunk(List<String> chunk) {
                    chunk.forEach(fluxSink::next);
                }

                @Override
                public void processComplete() {
                    fluxSink.complete();
                }
            });
        });
        Thread thread = new Thread() {
            @Override
            public void run() {
                System.out.println("publisher Thread name " + Thread.currentThread().getName());
                List<String> values = new LinkedList<>();
                MyEventListener<String> listener = listenerAtomicReference.get();
                for (int i = 1; i <= 100; i++) {
                    values.add(String.valueOf(i));
                    if (i % 10 == 0) {
                        listener.onDataChunk(values);
                        values.clear();
                    }
                }

                // 最终完成
                listener.processComplete();
            }
        };
        flux.subscribe(System.out::println);

        System.out.println("订阅完成");
        thread.start();

        thread.join();
    }

    /**
     * 此问题解决了 为什么subscribe 订阅之后 就立即返回的原因 ..
     */
    @Test
    public void asyncFluxWithCreate() {

        Flux.create(sink -> {
                    for (int i = 0; i < 3; i++) {
                        sink.next(i + 1);
                    }

                    sink.complete();
                    System.out.println("publisher Thread name " + Thread.currentThread().getName());
                })
                .subscribe(ele -> {
                    System.out.println("subscriber Thread name " + Thread.currentThread().getName());
                    System.out.println(ele);
                });

        System.out.println(" 异步处理   ");
        Flux.create(sink -> {
                    CompletableFuture.runAsync(() -> {
                        for (int i = 0; i < 3; i++) {
                            sink.next(i + 1);
                        }

                        sink.complete();
                    });
                    System.out.println("publisher Thread name " + Thread.currentThread().getName());
                })
                .subscribe(ele -> {
                    System.out.println("subscriber Thread name " + Thread.currentThread().getName());
                    System.out.println(ele);
                });

        System.out.println("结束");
    }


    // OverflowStrategy

    @Test
    public void overflowStrategy() throws InterruptedException {
        Assertions.assertThrows(IllegalStateException.class, () -> {
            testOverflowStrategy(OverflowStrategy.IGNORE);
        }, () -> {
            System.out.println("ignore 策略 抛出了 异常");
            return "ignore exception";
        });

        Assertions.assertThrows(IllegalStateException.class, () -> {
            testOverflowStrategy(OverflowStrategy.ERROR);
        }, () -> {
            System.out.println("error 策略 抛出了 异常");
            return "error exception";
        });


        testOverflowStrategy(OverflowStrategy.DROP);
        testOverflowStrategy(OverflowStrategy.LATEST);
        testOverflowStrategy(OverflowStrategy.BUFFER);
    }

    private static void testOverflowStrategy(OverflowStrategy strategy) {
        System.out.println("Testing OverflowStrategy: " + strategy);
        Flux.create(fluxSink -> {
                    for (int i = 0; i < 1000; i++) {
                        fluxSink.next(i);
                    }
                    fluxSink.complete();
//                    System.out.println("发送完毕");
                }, strategy)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(value -> {
                    // 模拟一些处理时间
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

//                    System.out.println("doNext");
                })
                .blockLast();
    }


    @Test
    public void hybridPullPush() throws InterruptedException {

        AtomicReference<MyEventListener<String>> reference = new AtomicReference<>();

        Flux.create(sink -> {
                    // 刚开始不给任何数据,那么请求的时候,就会触发 onRequest
                    sink.onRequest(new LongConsumer() {
                        @Override
                        public void accept(long value) {
                            System.out.println("接收到 " + value + "请求");
                        }
                    });

                    List<CompletableFuture<Void>> values = new LinkedList<>();
                    reference.set(new MyEventListener<String>() {
                        @Override
                        public void onDataChunk(List<String> chunk) {
                            values.add(CompletableFuture.runAsync(() -> {
                                // 当数据直接可用的时候,直接推送
                                for (String s : chunk) {
                                    sink.next(s);
                                }
                            }));

                            System.out.println("现在接收到的来自下游的请求数量" + sink.requestedFromDownstream());
                        }

                        @Override
                        public void processComplete() {
                            System.out.println("现在来自下游的请求数量" + sink.requestedFromDownstream());

                            CompletableFuture.allOf(values.toArray(CompletableFuture[]::new))
                                    .thenAccept((ele) -> {
                                        sink.complete();
                                    })
                                    // 阻塞等待
                                    .join();

                        }
                    });
                })
                .doOnComplete(() -> {
                    System.out.println("结束");
                })
                .subscribe(System.out::println);

        Thread thread = new Thread(() -> {
            MyEventListener<String> listener = reference.get();
            for (int i = 0; i < 10; i++) {
                listener.onDataChunk(Stream.iterate(i * 10, t -> t + 1).map(String::valueOf).limit(10).collect(Collectors.toList()));
            }
            listener.processComplete();
        });

        thread.start();

        thread.join();
    }

    /**
     * 尝试主动拉取 .. 当可用的时候 .. 主动推
     * <p>
     * 也就是每次请求的时候,我们尝试产生对应多的数据
     * <p>
     * 说明了, 流的上游,可以随意的控制 数据的产生, 例如推 / 拉模式的切换 ...
     *
     * @throws InterruptedException
     */
    @Test
    public void hybridPullPush2() throws InterruptedException {

        AtomicReference<MyEventListener<Integer>> reference = new AtomicReference<>();

        AtomicInteger count = new AtomicInteger(0);

        Flux.create(sink -> {

                    AtomicBoolean isUnbound = new AtomicBoolean(false);
                    AtomicReference<CompletableFuture<Void>> unboundFuture = new AtomicReference<>();

                    List<CompletableFuture<Void>> values = new LinkedList<>();

                    reference.set(new MyEventListener<Integer>() {
                        @Override
                        public void onDataChunk(List<Integer> chunk) {
                            values.add(CompletableFuture.runAsync(() -> {
                                for (Integer integer : chunk) {
                                    sink.next(integer);
                                }
                            }));
                        }

                        // 阻塞
                        @Override
                        public void processComplete() {
                            //等待
                            CompletableFuture.allOf(values.toArray(CompletableFuture[]::new))
                                    .thenAccept((e) -> {
                                        sink.complete();
                                        System.out.println("所有的元素已经处理完成 ..");
                                    })
                                    // 阻塞
                                    .join();
                        }
                    });

                    sink.onRequest(n -> {
                        System.out.println("请求的元素个数" + n);

                        // 数据是对的 ..
                        while (true) {
                            boolean b = isUnbound.get();
                            if (isUnbound.compareAndSet(b, b)) {
                                if (b) {
                                    if (n < Integer.MAX_VALUE) {
                                        CompletableFuture<Void> future = unboundFuture.get();
                                        if (future != null) {
                                            if (!future.isDone()) {
                                                unboundFuture.get().cancel(true);
                                                unboundFuture.set(null);
                                            }
                                        }
                                    }

                                    // 表示
                                    return;
                                }
                                break;
                            }
                        }

                        if (Integer.MAX_VALUE >= n) {
                            // 如果已经是true,没必要处理 ..
                            if (isUnbound.compareAndSet(false, true)) {
                                // 尝试周期性的增加 ..
                                CompletableFuture.runAsync(() -> {
                                    Flux.interval(Duration.ofMillis(1)).doOnNext(sink::next).subscribe();
                                });
                            }
                        }


                    });
                })
                .subscribe(new BaseSubscriber<>() {


                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        subscription.request(100);
                    }

                    @Override
                    protected void hookOnNext(Object value) {
                        count.incrementAndGet();

                        // 这里请求了一个无界
                        // 导致推送了很多数据 ..
                        if (count.compareAndSet(100, 100)) {
                            requestUnbounded();
                        }

                        // 最终又推送了300个
                        if (count.compareAndSet(200, 200)) {
                            request(300);
                        }


                        System.out.println("current thread name" + Thread.currentThread().getName() + " value: " + value);
                        // 总共需要500个(但是interval 可能导致甚至上千个) ..
                    }
                });

        Thread thread = new Thread(() -> {

            MyEventListener<Integer> integerMyEventListener = reference.get();

            integerMyEventListener.onDataChunk(
                    Stream.iterate(0, i -> i + 1).map(ele -> ele + 100000).limit(100).collect(Collectors.toList())
            );

            try {
                TimeUnit.SECONDS.sleep(3);
            } catch (InterruptedException e) {
                // pass
            }

            integerMyEventListener.onDataChunk(
                    Stream.iterate(101, e -> e + 1).map(ele -> ele + 200000).limit(200).collect(Collectors.toList())
            );

            // 直接完成
            integerMyEventListener.processComplete();
        });

        thread.start();

        // 这里阻塞等待程序完成 .
        thread.join();
    }


    @Test
    public void handleAsMap() {


        Flux.just(1, 2, 3, 4, 5)
                .handle((ele, sink) -> {
                    sink.next(ele * 2);
                })
                .subscribe(System.out::println);

    }

    /**
     * 从标准输出中我们可以看出,订阅顺序 ..
     * <p>
     * 以及订阅所发送的request 量,
     * <p>
     * 以及publishOn 重放动作,到第二个map 执行在parallel绑定的线程上 ..
     * <p>
     * 包括订阅的回调执行在parallel绑定的线程之上 ...
     * <p>
     * <p>
     * 对于request的变化是因为,publishOn 改变了下游操作符的请求处理 ..
     * <p>
     * <p>
     * 对于最后一个订阅来说,subscribe它本身是一个unbound 请求量,
     * <p>
     * 然后执行在第二个map的时候,依旧是unbound,但是从publishOn开始的时候, publishOn的prefetch 语义是默认256,所以就导致 它本身的订阅变成了请求 256个, 然后后续的第一个map 也就是256个 ...
     * <p>
     * 由于存在prefetch 语义,当达到了75%的一个发送率之后,会再次请求, 所以它优化了下游的一个快速请求过程(fast push) ..
     *
     * @throws InterruptedException
     */
    @Test
    public void publishOn() throws InterruptedException {
        Scheduler s = Schedulers.newParallel("parallel-scheduler", 4);

        final Flux<String> flux = Flux
                .range(1, 2)
                .log()
                .map(i -> 10 + i)
                .log()
                .publishOn(s)
                .log()
                .map(i -> "value " + i)
                .log();


        new Thread(() -> flux.subscribe(ele -> {
            System.out.println(Thread.currentThread().getName() + " value: " + ele);
        })).start();


        synchronized (Thread.currentThread()) {
            Thread.currentThread().wait(2000);
        }
    }

    /**
     * 这个示例中可以发现, subscribeOn 之后的, 操作符,在订阅的时候, 会先在原始的线程中走一遍订阅,然后随后偏移给subscribeOn ..
     * <p>
     * 所以,最好的写法就是 尽量让subscribeOn 靠后,这样直接立即切换到对应的scheduler上 ..
     * <p>
     * 当然不管什么位置,最终的结果就是偏移到对应的scheduler, 只是说在原始的线程执行的时间长短问题 。。。
     * <p>
     * <p>
     * 详细链接: <a href="https://projectreactor.io/docs/core/release/reference/#_the_subscribeon_method">subscribeOn</a>
     *
     * @throws InterruptedException
     */
    @Test
    public void subscribeOn() throws InterruptedException {
        Scheduler s = Schedulers.newParallel("parallel-scheduler", 4);

        final Flux<String> flux = Flux
                .range(1, 200)
                .log()
                .map(i -> 10 + i)
                .log()
                .subscribeOn(s)
                .log()
                .map(i -> {
                    System.out.println("Thread current : " + Thread.currentThread().getName() + " " + "value " + i);
                    return "value " + i;
                })
                .log();

        // 由于订阅的 上下文不同,需要保证线程是否被杀掉 ..
        Disposable subscribe = flux.subscribe(System.out::println);


        // 此时我们可以阻塞等待
        while (!subscribe.isDisposed()) {
            TimeUnit.SECONDS.sleep(1);
        }
        System.out.println("over");

    }


    /**
     * 这是一个全局的异常错误处理器的使用
     * <p>
     * 这里已经给使用到对丢失错误的处理了 ...
     * <p>
     * 下面我们总是没有定义OnError 错误处理器,那么就不符合reactive stream 规范 ..
     * <p>
     * 所以不符合这些规范的形式flux 都需要使用hooks来兜底 ..
     */
    @Test
    public void errorHandler() {

        Hooks.onErrorDropped(ele -> {
            if (Exceptions.isErrorCallbackNotImplemented(ele)) {
                System.out.println("错误异常处理器没有实现");
            }
        });
        Flux.defer(() -> {
                    System.out.println("Thread current " + Thread.currentThread().getName());
                    int value = 2 / 0;

                    return null;
                })
                .doOnError(e -> {
                    if (Exceptions.isErrorCallbackNotImplemented(e)) {
                        System.out.println("onError is not implemented !!!" + e.getMessage());
                    }

                    System.out.println("异常");
                })
                .subscribe(new BaseSubscriber<Object>() {

                });
    }

    /**
     * 最终释放资源的hook  是一种边缘操作符
     */
    @Test
    public void doFinally() {
        Flux.just(1, 2, 34)
                .doFinally(type -> {
                    System.out.println(type);
                })
                .subscribe();

        // no cancel
        Flux.just(1, 2, 3, 4)
                .doOnCancel(() -> {
                    System.out.println("cancel");
                })
                .subscribe();


        Flux.just(1, 2, 3, 4)
                .doOnCancel(() -> {
                    System.out.println("取消");
                })
                .doFinally(type -> {
                    System.out.println(type);
                })
                .take(2)
                .subscribe();
    }

    /**
     * using 操作符的使用 ..
     * <p>
     * 包含了对资源的最终处理
     */
    @Test
    public void using() {
        Flux.using(() -> 213, Flux::just, value -> {
                    System.out.println("清理");
                })
                .map(ele -> ele * 2)
                .map(ele -> ele + "value")
                .subscribe(System.out::println);

        System.out.println("处理");

        // 如果需要它提前释放,那么给定的flux 应该需要提前结束 才能够让它正常被清理掉 ..
        // 否则如果在一个线程上顺序执行,那么 eager 设置与否没有什么用 ..
        Flux.using(() -> 213, ele -> Flux.just(ele).publishOn(Schedulers.boundedElastic()), value -> {
                    System.out.println("清理");
                }, true)
                .publishOn(Schedulers.boundedElastic())
                .map(ele -> ele * 2)
                .map(ele -> {
                    System.out.println("处理 中");
                    return ele + "value";
                })
                .subscribe(System.out::println);
    }

    /**
     * 自定义Retry / 以及简单的最大重试次数判断
     * <p>
     * 其实还存在瞬时错误判断重试 .. 详情参考 文档 ..
     */
    @Test
    public void retryWhen() {
        Flux.just(1, 2, 3, 4)
                .map(ele -> {
                    if (ele == 3) {
                        throw new UnsupportedOperationException("3");
                    }
                    return ele;
                })
                .retryWhen(new Retry() {
                    @Override
                    public Publisher<?> generateCompanion(Flux<RetrySignal> flux) {

                        return flux.flatMap(singal -> {
                            if (singal.totalRetries() < 3) {
                                return Flux.just(singal);
                            }

                            // 通过 错误 来阻止重试,并向下传递错误信号
                            return Flux.error(singal.failure());
                        });
                    }
                })
                .doOnError(el -> {
                    System.out.println("发生错误" + el.getMessage());
                })
                .subscribe(System.out::println, throwable -> {
                    System.err.println("发生错误了 !!!");
                    System.err.println(throwable.getMessage());
                });



        System.out.println("重试次数跟伴生flux的元素数量有关,下面只会重试2次");
        Flux.just(1, 2, 3, 4)
                .map(ele -> {
                    if (ele == 3) {
                        throw new UnsupportedOperationException("3");
                    }
                    return ele;
                })
                .retryWhen(new Retry() {
                    @Override
                    public Publisher<?> generateCompanion(Flux<RetrySignal> flux) {

                        System.out.println("The Thread " + Thread.currentThread().getName());

                        System.out.println("伴生对象产生");
                        // 只会重试一次
                        return flux.take(1);
                    }
                })
                .subscribe(System.out::println);


    }


    @Test
    public void retryWhenPublisher() {
        Flux.just(1, 2, 3, 4)
                .map(ele -> {
                    if (ele == 3) {
                        throw new UnsupportedOperationException("3");
                    }
                    return ele;
                })
                .retryWhen(new Retry() {
                    @Override
                    public Publisher<?> generateCompanion(Flux<RetrySignal> flux) {

                        return flux.flatMap(singal -> {

                            if (singal.totalRetries() < 3) {
                                return Flux.just(singal);
                            }


                            // 通过 错误 来阻止重试,并向下传递错误信号
                            return Flux.empty();
                        });
                    }
                })
                .subscribe(System.out::println);
    }
    /**
     * take 结合 retryWhen 实现 Retry.max(n) 最大次数重试
     */
    @Test
    public void take() {
        Flux<String> flux = Flux
                .<String>error(new IllegalArgumentException())
                .doOnError(System.out::println)
                .retryWhen(Retry.from(companion ->
                {
                    return companion.take(3);
                }));

        flux.subscribe();

    }

    /**
     * hooks 的尝鲜
     * <p>
     * 并不一定可以测试通过
     */
    @Test
    public void hooks() {

        Hooks.onOperatorError("", new BiFunction<Throwable, Object, Throwable>() {
            @Override
            public Throwable apply(Throwable throwable, Object o) {
                return null;
            }
        });

    }

    @Test
    public void sinksForEmitResult() throws InterruptedException {

//        Sinks.Many<Object> many = Sinks.many().multicast().directAllOrNothing();
//
//        new Thread(() -> {
//            for (int i = 0; i < 100; i++) {
//                Sinks.EmitResult emitResult = many.tryEmitNext(i);
//                if(emitResult.isFailure()) {
//
////                    Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER
//                    System.out.println("cause reason: " + emitResult.name());
//                    break;
//                }
//            }
//        }).start();
//
//        TimeUnit.SECONDS.sleep(2);
//
////        Sinks.EmitResult.OK
//
//        new Thread(() -> {
//            many.asFlux().subscribe(new BaseSubscriber<Object>() {
//                @Override
//                protected void hookOnError(@NonNull Throwable throwable) {
//                    // swallow
//                }
//            });
//        }).start();
//
//        Thread.sleep(200);
//
//        new Thread(() -> {
//            for (int i = 0; i < 100; i++) {
//                Sinks.EmitResult emitResult = many.tryEmitNext(i);
//                if(emitResult.isFailure()) {
//                    System.out.println("cause reason: " + emitResult.name());
//                    return;
//                }
//            }
//
//            System.out.println("cause reason: " + Sinks.EmitResult.OK.name());
////            many.tryEmitComplete();
//            many.tryEmitError(new UnsupportedOperationException("不支持"));
//        }).start();
//
//        TimeUnit.SECONDS.sleep(2);
//
//        new Thread(() -> {
//            for (int i = 0; i < 100; i++) {
//                Sinks.EmitResult emitResult = many.tryEmitNext(i);
//                if(emitResult.isFailure()) {
//
////                    Sinks.EmitResult.FAIL_TERMINATED
//                    System.out.println("cause reason: " + emitResult.name());
//                    break;
//                }
//            }
//        }).start();
//
//        TimeUnit.SECONDS.sleep(2);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.EmitResult result;

        // Try to emit next value
        result = sink.tryEmitNext("Value 1");
        assertEquals(Sinks.EmitResult.OK, result);

        // Try to emit next value after termination
        sink.tryEmitComplete();
        result = sink.tryEmitNext("Value 2");
        assertEquals(Sinks.EmitResult.FAIL_TERMINATED, result);

        sink = Sinks.many().unicast().onBackpressureBuffer(Queues.<String>get(1).get());
        Sinks.Many<String> finalSink = sink;
        new Thread(() -> {
            finalSink.asFlux().subscribe(new BaseSubscriber<String>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    // 不请求任何数据
                }

                @Override
                protected void hookOnNext(String value) {
                    // 啥也不做 ..
                    try {
                        TimeUnit.SECONDS.sleep(30);
                    } catch (InterruptedException e) {
                        // pass
                    }
                }
            });
        }).start();

        TimeUnit.MILLISECONDS.sleep(500);
        // Try to emit next value with overflow
        result = sink.tryEmitNext("Value 3");
        result = sink.tryEmitNext("Value 3");

        System.out.println(result);
        assertEquals(Sinks.EmitResult.FAIL_OVERFLOW, result);


        // fail_cancelled


        Sinks.Many<Object> many = Sinks.many().multicast().onBackpressureBuffer();

        Flux<Object> flux = many.asFlux();

        flux.subscribe(new BaseSubscriber<Object>() {
            private int count = 0;

            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                CompletableFuture.runAsync(() -> {
                    subscription.cancel();
                });
            }

            @Override
            protected void hookOnError(Throwable throwable) {
                // pass
            }
        });

        System.out.println(many.currentSubscriberCount());

        Sinks.EmitResult emitResult = many.tryEmitNext("123");
        System.out.println(emitResult);

        emitResult = many.tryEmitNext("123");
        System.out.println(emitResult);


        // Try to emit next value with non-serialized access
        // 对于非序列化访问, 表示(一些操作 需要串行化处理)
        // 也就是如果出现并发对支持线程安全的Sink 进行 弹射,就会触发这些异常 ..
        // 当然Flux.create  除外 ..(但是它面向的对象最终是Flux)
        // 这里探讨的是Sink
        Sinks.Many<My> objectMany = Sinks.many().unicast().onBackpressureBuffer(Queues.<My>one().get());
//        objectMany.emitNext(new My(), (signalType, w) -> {
//            assertEquals(Sinks.EmitResult.FAIL_NON_SERIALIZED, w);
//            return false;
//        });

        // Try to emit next value with zero subscribers
        objectMany = Sinks.many().unicast().onBackpressureBuffer(Queues.<My>one().get());
        result = objectMany.tryEmitNext(new My());
        result = objectMany.tryEmitNext(new My());
        assertEquals(Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER, result);
    }

    // no serialize
    class My {

    }

    @Test
    public void sinkAutoCancel() {
        Sinks.Many<Object> many = Sinks.many().multicast().onBackpressureBuffer();

        Flux<Object> flux = many.asFlux();

//        for (int i = 0; i < 1000; i++) {
//            many.tryEmitNext(i);
//        }
        flux.subscribe(new BaseSubscriber<Object>() {
            private int count = 0;

            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                CompletableFuture.runAsync(() -> {
                    subscription.cancel();
                });
            }

            @Override
            protected void hookOnNext(Object value) {
                count++;
                if (count == 1) {
                    // 直接取消 ..
                    dispose();
                }
                // pass
            }

            @Override
            protected void hookOnError(Throwable throwable) {
                // pass
            }
        });

        System.out.println(many.currentSubscriberCount());

        Sinks.EmitResult emitResult = many.tryEmitNext("123");
        System.out.println(emitResult);

        emitResult = many.tryEmitNext("123");
        System.out.println(emitResult);

    }

    public static class SimpleSubScriber<T> extends BaseSubscriber<T> {

        private String name;

        private Consumer<T> consumer;

        public SimpleSubScriber(String name) {
            this(name, e -> {
            });
        }

        public SimpleSubScriber(String name, Consumer<T> consumer) {
            this.name = name;
            this.consumer = consumer;
        }

        @Override
        protected void hookOnNext(T value) {
            // pass
            consumer.accept(value);
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            System.out.println(name + "发生错误" + throwable);
        }
    }


    @Test
    public void sinksForManyAndMultiCastFor() throws InterruptedException {

        Sinks.Many<Object> many = Sinks.many().multicast().directAllOrNothing();

        CompletableFuture.runAsync(() -> {
            many.asFlux().subscribe(new SimpleSubScriber<>("one") {
                @Override
                protected void hookOnNext(Object value) {
                    System.out.println("sub1 " + value);
                }
            });
        });

        CompletableFuture.runAsync(() -> {
            many.asFlux().subscribe(new SimpleSubScriber<>("two") {
                @Override
                protected void hookOnNext(Object value) {
                    System.out.println("sub2 " + value);
                }
            });
        });

        CompletableFuture.runAsync(() -> {
            many.asFlux().subscribe(new SimpleSubScriber<>("three") {

                private int count = 0;

                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    // 不请求任何元素 ..
                    CompletableFuture.runAsync(() -> {
                        try {
                            TimeUnit.SECONDS.sleep(3);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        subscription.request(100);

                        System.out.println("请求 100个");
                    }).whenComplete((ele, throwa) -> {
                        System.out.println(ele);
                        if (throwa != null) {
                            System.out.println("订阅发生问题" + throwa);
                            cancel();
                        }
                    });
                }

                @Override
                protected void hookOnNext(Object value) {
                    count++;
                    System.out.println("订阅" + value);

                    if (count == 100) {
                        cancel();
                    }
                }
            });
        });


        while (many.currentSubscriberCount() <= 0) ;

        new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                // 一分钟试一次 ..
                int finalI = i;
                many.emitNext(i, new Sinks.EmitFailureHandler() {

                    @Override
                    public boolean onEmitFailure(SignalType signalType, Sinks.EmitResult emitResult) {
                        System.out.println("发生异常 " + finalI + " " + emitResult);
                        // 此busyLooping 不能重用
                        return true;
                    }
                });
            }

            many.tryEmitComplete();
        }).start();


        // 永不退出
        while (many.currentSubscriberCount() > 0) {
            TimeUnit.SECONDS.sleep(1);
        }
    }

    @Test
    public void sinksForManyAndMultiCastForBestEffort() throws InterruptedException {
        Sinks.Many<Object> many = Sinks.many().multicast().directBestEffort();

        Thread.sleep(20);

        Flux<Object> flux = many.asFlux();

        CompletableFuture.runAsync(() -> {
            flux.subscribe(e -> System.out.println("quickly: " + e));
        });

        CompletableFuture.runAsync(() -> {
            flux.subscribe(new BaseSubscriber<Object>() {
                @Override
                protected void hookOnSubscribe(Subscription subscription) {
                    // pass
                }

                @Override
                protected void hookOnError(Throwable throwable) {
                    // pass
                }
            });

            // 查看尽最大努力的不同之处
//            flux.subscribe(e -> {
//                try {
//                    TimeUnit.SECONDS.sleep(2);
//                } catch (InterruptedException ex) {
//                    // pass
//                }
//                System.out.println("slowly: " + e);
//            });
        });

        new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                // pass
            }

            for (int i = 0; i < 3600000; i++) {
                // 不管结果
                many.tryEmitNext(i);
            }

            many.tryEmitComplete();
        }).start();

        while (many.currentSubscriberCount() <= 0) {
            // 睡眠一秒
            TimeUnit.SECONDS.sleep(1);
        }

        while (many.currentSubscriberCount() > 0) {
            // 睡眠一秒
            TimeUnit.SECONDS.sleep(1);
        }
    }


    @Test
    public void sinksForReplay() throws InterruptedException {

        Sinks.Many<Object> latest = Sinks.many().replay().latest();

        latest.tryEmitNext("12");
        latest.tryEmitNext("14");
        latest.tryEmitNext("15");
        latest.tryEmitNext("16");

        latest.asFlux().subscribe(new SimpleSubScriber<>("one", e -> {
            System.out.println("Thread current name " + Thread.currentThread().getName() + " one: " + e);
        }));
        System.out.println("订阅完毕");
        latest.tryEmitNext("17");
        latest.asFlux().subscribe(new SimpleSubScriber<>("two", e -> {
            System.out.println("Thread current name " + Thread.currentThread().getName() + " two: " + e);
        }));
        System.out.println("订阅完毕");
        CompletableFuture.runAsync(() -> {
            latest.asFlux().subscribe(new SimpleSubScriber<>("async-one", e -> {
                System.out.println("Thread current name " + Thread.currentThread().getName() + " async-0: " + e);
            }));
        });
        CompletableFuture.runAsync(() -> {
            latest.asFlux().subscribe(new SimpleSubScriber<>("async-two", e -> {
                System.out.println("Thread current name " + Thread.currentThread().getName() + " async-1: " + e);
            }));
        });

        for (int i = 0; i < 10; i++) {
            latest.tryEmitNext(i);
        }

        latest.tryEmitComplete();

        while (latest.currentSubscriberCount() > 0) {
            TimeUnit.SECONDS.sleep(1);
        }

    }

    @Test
    public void retrySinkForLatestOrDefault() {
        // 同上,但是例如没有弹射值的时候,则使用默认值
        Sinks.Many<String> defaultValue = Sinks.many().replay().latestOrDefault("default value");

        defaultValue.asFlux()
                .subscribe(new SimpleSubScriber<>("default", System.out::println));


    }

    @Test
    public void transform() {
        Flux.just(1, 2, 3, 4)
                .all(ele -> ele > 3)
                .subscribe(System.out::println);

        Flux.just(1, 2, 3, 4)
                .hasElement(5)
                .subscribe(System.out::println);

    }

    @Test
    public void coordinate() {
        Mono.just(1).and(Mono.error(new UnsupportedOperationException()))
                .subscribe(new SimpleSubScriber<>("one"));

        Mono.error(new UnsupportedOperationException()).and(Mono.just(1))
                .subscribe(new SimpleSubScriber<>("two"));


        Flux.zip(Flux.just(1, 2, 3, 4), Flux.error(new UnsupportedOperationException()))
                .subscribe(new SimpleSubScriber<>("three"));


        Flux.zip(Flux.just(1, 2, 3, 4), Flux.error(new UnsupportedOperationException()))
                .onErrorReturn(Tuples.of(0, 0))
                .subscribe(new SimpleSubScriber<>("three", System.out::println));


        Flux.mergeDelayError(2, Flux.just(1, 2, 3, 4), Flux.error(new UnsupportedOperationException()))
                .subscribe(new SimpleSubScriber<>("five", System.out::println));

        Flux.mergeSequential(Flux.just(1, 2, 3, 4), Flux.just(1, 2))
                .subscribe(new SimpleSubScriber<>("six", System.out::println));

        Flux.mergeSequential(Flux.just(1, 2, 3, 4), Flux.error(new UnsupportedOperationException()))
                // 这可能会导致 多产生一个值
//                .onErrorReturn(0)
                // 返回一个空值
                .onErrorResume((e) -> Flux.empty())
                .subscribe(new SimpleSubScriber<>("seven", System.out::println));

        Flux.just(1, 2, 3, 4).switchMap(ele -> {
            return Flux.fromStream(Stream.iterate(0, e -> e + 1).limit(ele));
        }).subscribe(new SimpleSubScriber<>("eight", ele -> {
            System.out.println("eight: ");
            System.out.println(ele);
        }));
    }

    @Test
    public void firstWithValue() {
        Flux.firstWithValue(Flux.just(1), Flux.just(2)).subscribe(new SimpleSubScriber<>("firstWithValue", System.out::println));


        Flux.firstWithValue(Flux.just(1)
                        .publishOn(Schedulers.boundedElastic())
                        .flatMap(el -> {
                            try {
                                TimeUnit.SECONDS.sleep(1);
                            } catch (Exception e) {
                                // pass
                            }
                            return Flux.just(el);
                        })
                , Flux.just(2).publishOn(Schedulers.boundedElastic())).subscribe(new SimpleSubScriber<>("firstWithValue", System.out::println));


        Flux.firstWithSignal(Flux.just(1)
                        .publishOn(Schedulers.boundedElastic())
                        .flatMap(el -> {
                            try {
                                TimeUnit.SECONDS.sleep(1);
                            } catch (Exception e) {
                                // pass
                            }
                            return Flux.just(el);
                        })
                , Flux.just(2).publishOn(Schedulers.boundedElastic())).subscribe(new SimpleSubScriber<>("firstWithSignal", System.out::println));
    }


    @Test
    public void switchMapForInnerPublishers() throws InterruptedException {
//
//        Flux.just(1, 2, 3, 4).flatMap(
//                        ele -> Flux.just(ele)
//                                // 通过将Flux 切换到其他线程上执行 才能实现真正的merge
//                                .publishOn(Schedulers.boundedElastic())
//                                .map(el -> {
//                                    try {
//                                        Thread.sleep((4 - ele) * 1000L);
//                                        System.out.println("Thread current name:" + Thread.currentThread().getName() + " ele:" + ele);
//                                    } catch (InterruptedException e) {
//                                        // pass
//                                    }
//                                    return ele * 10;
//                                })
//                )
//                .subscribe(new SimpleSubScriber<>("one", ele -> {
//                    System.out.println("Thread current name: " + Thread.currentThread().getName());
//                    System.out.println(ele);
//                }));


        // switchMap 在切换流之后,不关心原有上游的flux的状态
        // 假如这里是一个Flux<Publisher<?> 那么switchMap的时候,将一个原有的publisher 切换到新的publisher ..
        // 但是到底依不依赖 关不关心原有发布者的状态,取决于开发者 ..

        // 你会发现仅有 40(也就是第4的一个元素所产生Flux 发生动作), 之前的 通过元素产生的flux 将会丢弃 ..
        Flux.just(1, 2, 3, 4)
                .switchMap(ele -> {
                    return Flux.just(ele)
                            .publishOn(Schedulers.boundedElastic())
                            .flatMap(it -> {
                                try {
                                    Thread.sleep((4 - ele) * 1000L);
                                    System.out.println("Thread current name:" + Thread.currentThread().getName() + " ele:" + ele);
                                } catch (InterruptedException e) {
                                    // pass
                                }
                                return Flux.just(ele * 10);
                            });
                })
                .subscribe(new SimpleSubScriber<>("two", System.out::println));


        Thread.sleep(1000);



        Flux.just(1,2,3,4)
                .switchMap(e -> {
                    return Flux.just(e)
                            .publishOn(Schedulers.boundedElastic())
                            .map(it -> {
                                try {
                                    Thread.sleep(e - 1);
                                } catch (InterruptedException ex) {
                                    // pass
                                }
                                return it;
                            })
                            .thenMany(Flux.interval(Duration.ofMillis(250)).map(it -> e + "-" + it));
                })
                        .subscribe(new BaseSubscriber<String>() {
                            @Override
                            protected void hookOnSubscribe(Subscription subscription) {
                                // 请求一个 ..
                                subscription.request(4);

                                CompletableFuture.runAsync(() -> {
                                    try {
                                        TimeUnit.SECONDS.sleep(1);
                                    } catch (InterruptedException e) {
//                                        throw new RuntimeException(e);
                                    }

                                    subscription.request(100);
                                });
                            }

                            @Override
                            protected void hookOnNext(String value) {
                                System.out.println(value);
                            }
                        });

        Thread.sleep(5000);
    }

    @Test
    public void switchOnContext() throws InterruptedException {

        Flux.switchOnNext(Flux.just(1, 2, 3, 4).switchMap(ele -> {
                    return Flux.just(Flux.just(ele)

                            // 不加此publishOn, 意味着会依次处理(因为没有并行)
                            // 此时由于第一个flux 需要的时间更多,所以 它没有完成 则后续的无法完成 ..

                            // 加上次操作符会导致, 并行处理 .
                            // 那么最后一个flux 会立即返回,将只会订阅此flux(因为 switchOnNext操作符的语义)
                            .publishOn(Schedulers.boundedElastic())
                            .flatMap(it -> {
                                try {
                                    Thread.sleep((4 - ele) * 1000L);
                                    System.out.println("Thread current name:" + Thread.currentThread().getName() + " ele:" + ele);
                                } catch (InterruptedException e) {
                                    // pass
                                }
                                return Flux.just(ele * 10);
                            }));
                }))
                .subscribe(new SimpleSubScriber<>("switchOnNext", System.out::println));

        Thread.sleep(5000);
    }


    @Test
    public void then() throws InterruptedException {
        Flux.just(1)
                .ignoreElements()
                .thenEmpty(Flux.just(2).then())
                .then()
//                .doOnSuccess() 和 Flux.doOnComplete() 对立
                .subscribe(null, null, () -> System.out.println("over"));


        Flux.error(new Throwable())
                .then()
                .subscribe(new SimpleSubScriber<>("error"));


        Flux.just(1).delayUntil(e -> {
                    return Flux.just(e)
                            .flatMap(el -> {
                                try {
                                    TimeUnit.SECONDS.sleep(1);
                                } catch (InterruptedException ex) {
                                    // pass
                                }
                                return Flux.just(el);
                            });
                })
                .doOnComplete(() -> {
                    System.out.println("over for delay Until");
                })
                .subscribe();
    }

    @Test
    public void expand() {
        // 采用宽度优先算法
        // 也就是先弹出第一批数据
        // 然后继续处理第二层/ 递归处理
        Flux.just(1, 2).expand(e -> Flux.fromStream(Stream.iterate(0, el -> el + 1).limit(e)))
                .subscribe(new SimpleSubScriber<>("expand", System.out::println));

        // 采用深度优先算法
        // 每次先弹出 flux中的一个元素,然后扩展它

        // 并拿出扩展的flux中的第一个元素弹出,继续取出它的扩展flux中的元素 弹出(并尝试扩展) 一直递归 ..

        // 也就是永远拿到当前弹出元素(它可能位于很深的位置)的扩展的flux 进行处理, 直到扩展的flux 为空
        // 向上寻找此弹出元素的兄弟元素  继续递归此过程 ..
        System.out.println("deep");
        Flux.just(1, 2, 3, 4).expandDeep(e -> Flux.fromStream(Stream.iterate(0, el -> el + 1).limit(e)))
                .subscribe(new SimpleSubScriber<>("expandDeep", System.out::println));
    }

    @Test
    public void cancel() {
        // 取消算不算中断
        Flux.just(1, 2, 3, 4, 5, 6)
                // 没有执行
                .doOnTerminate(() -> System.out.println("通过取消完成"))
                .doOnCancel(() -> {
                    System.out.println("发生了取消动作");
                })
                // 没有完成
                .doOnComplete(() -> {
                    System.out.println("由于取消,完成了flux");
                })
                // 没有执行
                .doOnError((e) -> {
                    System.out.println("由于取消,失败");
                })
                .subscribe(new BaseSubscriber<Integer>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        subscription.cancel();
                    }

                    @Override
                    protected void hookOnComplete() {
                        System.out.println("完成");
                    }

                    @Override
                    protected void hookOnError(Throwable throwable) {
                        System.out.println("错误");
                    }

                    @Override
                    protected void hookOnCancel() {
                        System.out.println("订阅者取消");
                    }
                });
    }

    @Test
    public void signal() {
        Flux.just(1, 2, 3, 4)
                .doOnEach(signal -> {
                    SignalType type = signal.getType();

                    // 处理每一个信号
                })
                .materialize()
                .doOnNext(el -> {
                    System.out.println(el.getType().name());
                })
                .dematerialize()
                .subscribe(new SimpleSubScriber<>("signal", System.out::println));
    }

    @Test
    public void filter() throws InterruptedException {
        Flux.just(1, 2, 2, 3, 4)
                .filterWhen(e -> Flux.just(true))
                .subscribe();


        // 只要奇数
        Flux.just(1, 2, 3, 4)
                .distinctUntilChanged(new Function<Integer, Object>() {
                    @Override
                    public Object apply(Integer integer) {

                        // 奇偶数交替 // 导致key 一直在改变 ..
                        // 连续key 存在,则考虑丢弃

                        if (integer <= 3) {
                            return "low";
                        }

                        return integer;
                    }
                })
                .subscribe(System.out::println);


        Flux.just(1, 2, 2, 3, 4, 4).distinct().subscribe(System.out::println);

        System.out.println("takeUtilOther");

        // 这里就是拿取20秒内的流中的数据
        Flux.interval(Duration.ofMillis(250)).takeUntilOther(Flux.just(1, 2, 3, 4,5,6)
                        .publishOn(Schedulers.boundedElastic())
                        .flatMap(ee -> {

                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ex) {
                                // pass
                            }

//                            1秒 = 250毫秒 = 4个元素
                            // 5秒 =  20个元素
                            if (ee < 5) {

                                // 通过弹出empty,表示没有空元素,相当于没有 ..
                                return Flux.empty();
                            }

                            return Flux.just(ee);
                        }))
                .subscribe(new BaseSubscriber<Long>() {
                    @Override
                    protected void hookOnNext(Long value) {
                        System.out.println("hook " + value);
                    }
                });

        Thread.sleep(6000);

    }


    @Test
    public void timed() throws InterruptedException {
        Flux.interval(Duration.ofMillis(250)).take(3)
//                .timestamp()
                .elapsed()
                .subscribe(new SimpleSubScriber<>("timed for legacy timed",System.out::println));

        // 由于interval 使用的 Schedulers 的原因

        // 这里我们阻塞等待 1秒
        TimeUnit.SECONDS.sleep(1);
    }

    @Test
    public void delayForMono() {
        Mono.delay(Duration.ofMillis(256))
                .subscribe(e -> {
                    Assertions.assertEquals(0,e);
                });
    }

    @Test
    public void splitFlux() {
        Flux.just(1,2,3,4)
                .windowUntil(e -> {
                    // 每当条件成立 则开启一个窗口
                    return e % 2 == 0;
                })
                .doOnNext(e -> {
                    e.subscribe(value -> {
                        System.out.print(value + " ");
                    });
                    System.out.println();
                })
                .subscribe();
    }

    @Test
    public void block() {
        Integer integer = Flux.just(1, 2, 3, 4)
                .blockLast();


        Assertions.assertEquals(4,integer);

        Object o = Flux.empty()
                .blockLast();

        Assertions.assertNull(o);
    }

    @Test
    public void multicastForFlux() throws InterruptedException {
        ConnectableFlux<Integer> publish = Flux.just(1, 2, 3, 4)
                .doOnNext(e -> {
                    System.out.println("do Next");
                })
                .publish();

        // 安静订阅
        // 这里已经阻塞了 ..
        Disposable connect = publish.connect();
        System.out.println("触发");

        // 共享
        Flux<Long> take = Flux.interval(Duration.ofMillis(250)).take(30)

                // 流的取消和中断不会同时发生
                .doOnCancel(() -> {
                    System.out.println("流取消了 ...");
                })

                // 当在shared上的所有订阅者结束之后,会取消原始流 ..
                //
                .doOnTerminate(() -> {
                    System.out.println("中断");
                });
        Flux<Long> share = take.share();

        CompletableFuture.runAsync(() -> {
            share.subscribe(new BaseSubscriber<Long>() {

                private int count = 0;
                @Override
                protected void hookOnNext(Long value) {
                    count ++;
                    if(count == 20) {
                        cancel();
                    }
                    System.out.println("one: " + value);
                }
            });
        });
        TimeUnit.SECONDS.sleep(3);
        CompletableFuture.runAsync(() -> {
            share.subscribe(new BaseSubscriber<Long>() {
                private int count = 0;
                @Override
                protected void hookOnNext(Long value) {
                    count ++ ;
                    if(count == 5) {
                        cancel();
                    }
                    System.out.println("two: " + value);
                }
            });
        });

        // 这一段代码将改变 源流的最终状态(要么取消或者中断)
        // 订阅阻止 线程死亡
//        Disposable subscribe = share.subscribe();

//        while(!subscribe.isDisposed()) {
//            TimeUnit.SECONDS.sleep(1);
//        }

        // 开启上面的代码请注释下面的代码
        Thread.sleep(15000);

    }

    @Test
    public void takeWhile() {

//        Flux.just(1,2,3,4,5)
////                .takeWhile(e  -> e < 4)
//                // 会包括边界 ..(如果不需要5),那么则需要 - 2
//                .takeUntil(e -> e > 3)
//                .subscribe(System.out::println);

        // 直到什么时候,才中继值
        Flux.just(1,2,3,4,5)
                // 从一开始进行true,否则 只要为false,将中断流
                // 中继值 直到条件为false
                .takeWhile(e -> e > 3)
                .subscribe(System.out::println);
    }

    @Test
    public void errorResume() {
        Flux.just(1,2,3,4,5)
                .map(e -> {
                    if(e == 2) {
                        throw new UnsupportedOperationException();
                    }
                    return e;
                })
                .onErrorReturn(2)
                .subscribe(System.out::println);

        System.out.println("onErrorResume");

        Flux.just(1,2,3,4,5)
                .map(e -> {
                    if(e == 2) {
                        throw new UnsupportedOperationException();
                    }
                    return e;
                })
                .onErrorResume(t -> {
                    return Flux.empty();
                })
                .subscribe(System.out::println);
    }
}
