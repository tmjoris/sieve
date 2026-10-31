package dev.sieve.grpc;

import dev.sieve.Admission;
import dev.sieve.Priority;
import dev.sieve.Sieve;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sheds gRPC calls before they reach the service. The priority comes from the
 * {@code x-sieve-priority} metadata key. A shed call is closed with {@code UNAVAILABLE} and a
 * {@code retry-after} trailer (seconds), which is the status Netflix's clients retry on.
 */
public final class SieveServerInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> PRIORITY_KEY =
            Metadata.Key.of("x-sieve-priority", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> RETRY_AFTER_KEY =
            Metadata.Key.of("retry-after", Metadata.ASCII_STRING_MARSHALLER);

    private final Sieve sieve;
    private final Priority fallback;

    public SieveServerInterceptor(Sieve sieve, Priority fallback) {
        this.sieve = sieve;
        this.fallback = fallback;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Priority priority = Priority.parse(headers.get(PRIORITY_KEY)).orElse(fallback);

        switch (sieve.admit(priority)) {
            case Admission.Shed shed -> {
                Metadata trailers = new Metadata();
                trailers.put(RETRY_AFTER_KEY, Long.toString(shed.retryAfter().toSeconds()));
                call.close(Status.UNAVAILABLE.withDescription(
                        "shed (" + shed.reason().name().toLowerCase() + ")"), trailers);
                return new ServerCall.Listener<>() {};
            }
            case Admission.Admitted admitted -> {
                AtomicBoolean finished = new AtomicBoolean();
                ServerCall<ReqT, RespT> tracked = new SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        finish(admitted.permit(), finished, status);
                        super.close(status, trailers);
                    }
                };
                ServerCall.Listener<ReqT> listener;
                try {
                    listener = next.startCall(tracked, headers);
                } catch (RuntimeException e) {
                    finish(admitted.permit(), finished, Status.INTERNAL);
                    throw e;
                }
                return new SimpleForwardingServerCallListener<>(listener) {
                    @Override
                    public void onCancel() {
                        finish(admitted.permit(), finished, Status.CANCELLED);
                        super.onCancel();
                    }
                };
            }
        }
    }

    /** Report the call's outcome to Sieve exactly once. */
    private static void finish(Admission.Permit permit, AtomicBoolean finished, Status status) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        switch (status.getCode()) {
            case OK -> permit.success();
            // Signals that the service itself is struggling.
            case DEADLINE_EXCEEDED, UNAVAILABLE, RESOURCE_EXHAUSTED, INTERNAL, UNKNOWN ->
                    permit.dropped();
            // Client errors and client-side cancellation say nothing about our capacity.
            default -> permit.ignore();
        }
    }
}
