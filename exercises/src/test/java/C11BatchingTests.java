import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author jasonj
 * @date 2023/6/18
 * @time 11:50
 * @description
 **/
public class C11BatchingTests {

    @Test
    public void group() {
        StepVerifier.create(
                        Flux.just(1, 3, 5, 2, 4, 6, 11, 12, 13)
                                .groupBy(i -> i % 2 == 0 ? "even" : "odd")
                                .concatMap(g -> g.defaultIfEmpty(-1) //if empty groups, show them
                                        .map(String::valueOf) //map to string
                                        .startWith(g.key())) //start with the group's key
                )
                .expectNext("odd", "1", "3", "5", "11", "13")
                .expectNext("even", "2", "4", "6", "12")
                .verifyComplete();

        StepVerifier.create(
                        Flux.just(1, 2, 3, 4, 5, 6, 7)
                                .groupBy(i -> i <= 7 ? "low" : "high")
                                .concatMap(g -> g.defaultIfEmpty(-1)
                                        .map(String::valueOf)
                                        .startWith(g.key()))
                )
                .expectNext("low", "1", "2", "3", "4", "5", "6", "7")
                .verifyComplete();
    }

    @Test
    public void windowUntilOrWhile() throws InterruptedException {

        // javadoc中说,如果以一个分隔符结尾的窗口将不会弹射 (也就是不存在)
        // 但是一分隔符开头的或者多个 都可以是空的窗口
        // 当条件成立,将保持窗口持续打开 ..

        // 否则窗口关闭,并且抛弃触发条件的元素 ..
        // 下面,刚开始创建了一个窗口,但是由于条件触发, 窗口关闭,并且1 抛弃
        // 同理 3 ,5 空的窗口
        // 于是 2,4,6 属于同一个窗口

        // 11 被抛弃(但是不会产生窗口)

        // 于是 12窗口打开,并且在13 触发窗口关闭
        StepVerifier.create(Flux.just(1, 3, 5, 2, 4, 6, 11, 12, 13)
                        .windowWhile(i -> i % 2 == 0)
                        .concatMap(g -> g.defaultIfEmpty(-1)))
                .expectSubscription()
                .expectNext(-1, -1, -1)
                .expectNext(2, 4, 6)
                .expectNext(12)
                .verifyComplete();


        // 在下面的示例中
        // 2 触发新序列,但是放在前一个序列中(4 开始)
        // 4触发新序列,放在前一个序列(6 开始)
        // 6 触发新序列(11 开始
        // 12 放在前一个序列中 ..(13开始)
        StepVerifier.create(Flux.just(1, 3, 5, 2, 4, 6, 11, 12, 13)
                        .windowUntil(i -> i % 2 == 0)
                        .concatMap(g -> g.concatWith(Mono.just(-1))))
                .expectNext(1, 3, 5, 2)
                .expectNext(-1)
                .expectNext(4)
                .expectNext(-1)
                .expectNext(6)
                .expectNext(-1)
                .expectNext(11, 12)
                .expectNext(-1)
                .expectNext(13)
                .expectNext(-1)
                .verifyComplete();


        // cutBefore  = true, 表示将关闭窗口的触发条件的元素放在新窗口序列中 ..
        // 2 触发,放在新的序列中
        // 4 新序列
        // 6 新序列
        // 12 新序列
        StepVerifier.create(Flux.just(1, 3, 5, 2, 4, 6, 11, 12, 13)
                        .windowUntil(i -> i % 2 == 0, true)
                        .concatMap(g -> g.concatWith(Mono.just(-1))))
                .expectNext(1, 3, 5)
                .expectNext(-1)
                .expectNext(2)
                .expectNext(-1)
                .expectNext(4)
                .expectNext(-1)
                .expectNext(6, 11)
                .expectNext(-1)
                .expectNext(12, 13)
                .expectNext(-1)
                .verifyComplete();


    }

    // buffer 类似于 window 并且具有类似的操作符 ..
    // 但是buffer 负责创建新的集合 / window 负责创建新的窗口

    // 并且这里对应的window 操作符打开window, 那么buffer 操作符对应是创建新集合并开始增加元素
    // 当窗口关闭,buffer 操作符也会弹射集合 ..

    // buffer 能够删除原始原始 或者 具有重叠的buffer
    @Test
    public void buffering() {

        StepVerifier.create(
                        Flux.range(1, 10)
                                .buffer(5, 3) //overlapping buffers
                )
                .expectNext(Arrays.asList(1, 2, 3, 4, 5))
                .expectNext(Arrays.asList(4, 5, 6, 7, 8))
                .expectNext(Arrays.asList(7, 8, 9, 10))
                .expectNext(Collections.singletonList(10))
                .verifyComplete();

        // 不像window,bufferUntil / bufferWhile 不会弹出空的buffer,如下所示

        StepVerifier.create(
                        Flux.just(1, 3, 5, 2, 4, 6, 11, 12, 13).bufferWhile(i -> i % 2 == 0)
                )
                .expectNext(Arrays.asList(2, 4, 6))
                .expectNext(Collections.singletonList(12));

        StepVerifier.create(
                        Flux.just(1, 3, 5, 2, 4, 6, 11, 12, 13).bufferUntil(i -> i % 2 == 0)
                ).expectNext(
                        Arrays.asList(1, 3, 5, 2)
                )
                .expectNext(Arrays.asList(4))
                .expectNext(Arrays.asList(6))
                .expectNext(Arrays.asList(11, 12))
                .expectNext(Arrays.asList(13))
                .verifyComplete();

        StepVerifier.create(

                        Flux.just(1, 3, 5, 2, 4, 6, 11, 12, 13)
                                .bufferUntil(i -> i % 2 == 0, true)
                )
                .expectNext(Arrays.asList(1, 3, 5))
                .expectNext(Arrays.asList(2))
                .expectNext(Arrays.asList(4))
                .expectNext(Arrays.asList(6, 11))
                .expectNext(Arrays.asList(12, 13))
                .verifyComplete();

//        Flux.just(1,3,5,2,4,6,11,12,13)
        // 这个比较难用 ..
        // 通过伴生flux 来决定 ..
//                .bufferWhen()
    }


}
