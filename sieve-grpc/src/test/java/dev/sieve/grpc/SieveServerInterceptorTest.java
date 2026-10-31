package dev.sieve.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.netflix.concurrency.limits.limit.FixedLimit;
import dev.sieve.Priority;
import dev.sieve.PriorityLimiter;
import dev.sieve.Sieve;
import dev.sieve.TierShares;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SieveServerInterceptorTest {

    private static final MethodDescriptor.Marshaller<String> UTF8 = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String v) {
            return new ByteArrayInputStream(v.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream in) {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    };

    private static final MethodDescriptor<String, String> SAY = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Echo/Say")
            .setRequestMarshaller(UTF8)
            .setResponseMarshaller(UTF8)
            .build();

    private volatile CountDownLatch release = new CountDownLatch(0);
    private final CountDownLatch entered = new CountDownLatch(1);
    private Server server;
    private ManagedChannel channel;

    private void start(int limit) throws IOException {
        Sieve sieve = Sieve.builder(
                        new PriorityLimiter(FixedLimit.of(limit), TierShares.criticalFirst()))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("test.Echo")
                .addMethod(SAY, ServerCalls.asyncUnaryCall((req, obs) -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (req.equals("fail")) {
                        obs.onError(Status.INTERNAL.asRuntimeException());
                    } else {
                        obs.onNext("echo:" + req);
                        obs.onCompleted();
                    }
                }))
                .build();
        String name = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(name).executor(java.util.concurrent.Executors.newCachedThreadPool())
                .addService(ServerInterceptors.intercept(service,
                        new SieveServerInterceptor(sieve, Priority.BEST_EFFORT)))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    private Channel withPriority(String priority) {
        Metadata md = new Metadata();
        md.put(SieveServerInterceptor.PRIORITY_KEY, priority);
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(md));
    }

    private String call(String priority, String body) {
        return ClientCalls.blockingUnaryCall(withPriority(priority), SAY, CallOptions.DEFAULT, body);
    }

    @BeforeEach
    void setUp() {
        release = new CountDownLatch(0);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        release.countDown();
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void admittedCallSucceeds() throws Exception {
        start(10);
        assertEquals("echo:hi", call("critical", "hi"));
    }

    @Test
    void shedCallFailsUnavailableWithRetryAfter() throws Exception {
        start(1);
        release = new CountDownLatch(1);
        CompletableFuture<String> held = CompletableFuture.supplyAsync(() -> call("bulk", "first"));
        assertEquals(true, entered.await(5, TimeUnit.SECONDS));

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> call("bulk", "second"));
        assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
        Metadata trailers = e.getTrailers();
        assertNotNull(trailers);
        assertEquals("10", trailers.get(SieveServerInterceptor.RETRY_AFTER_KEY));

        release.countDown();
        assertEquals("echo:first", held.get(5, TimeUnit.SECONDS));
    }

    @Test
    void failedCallReleasesItsSlot() throws Exception {
        start(1);
        assertThrows(StatusRuntimeException.class, () -> call("bulk", "fail"));
        assertEquals("echo:ok", call("bulk", "ok"));
    }

    @Test
    void missingPriorityUsesFallback() throws Exception {
        start(10);
        String reply = ClientCalls.blockingUnaryCall(channel, SAY, CallOptions.DEFAULT, "x");
        assertEquals("echo:x", reply);
    }
}
