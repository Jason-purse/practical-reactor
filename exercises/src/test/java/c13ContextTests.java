import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.ContextView;

import java.util.function.BiFunction;

/**
 * @author jasonj
 * @date 2023/6/18
 * @time 19:42
 * @description
 **/
public class c13ContextTests {

    @Test
    public void test() {
        String key = "message";
        Mono<String> r = Mono.just("Hello")
                .flatMap(s -> {
                    return Mono.deferContextual(ctx -> {
                        System.out.println("value: " + ctx.get("name"));
                        return Mono.just(s);
                    });
                })
                .contextWrite(ctx -> ctx.put("name","flux"))
                .transformDeferredContextual(new BiFunction<Mono<String>, ContextView, Publisher<String>>() {
                    @Override
                    public Publisher<String> apply(Mono<String> stringMono, ContextView contextView) {
                        System.out.println(contextView.getOrEmpty("name").orElse("empty"));
                        contextView.stream().forEach(entry -> {
                            System.out.println("key " + entry.getKey() + " value " + entry.getValue());
                        });
                        return stringMono;
                    }
                })
                .flatMap(s -> Mono.deferContextual(ctx ->
                        Mono.just(s + " " + ctx.get(key))))
//                .transformDeferredContextual(new BiFunction<Mono<String>, ContextView, Publisher<String>>() {
//                    @Override
//                    public Publisher<String> apply(Mono<String> stringMono, ContextView ctx) {
//                        System.out.println("value: " + ctx.get("name"));
//                        return stringMono;
//                    }
//                })
                .contextWrite(ctx -> ctx.put(key, "World"));

        StepVerifier.create(r)
                .expectNext("Hello World")
                .verifyComplete();
    }
}
