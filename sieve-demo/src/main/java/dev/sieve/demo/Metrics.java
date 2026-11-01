package dev.sieve.demo;

import dev.sieve.Priority;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Minimal Prometheus text-format metrics: outcome counters and a latency histogram per tier. */
final class Metrics {

    enum Outcome {
        OK("ok"),
        SHED_UTILIZATION("shed_utilization"),
        SHED_LIMIT("shed_limit");

        final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    /** Geometric buckets, 1 ms to 30 s with a 1.25x step, so a bucket-estimated p99 is within 25%. */
    private static final double[] BUCKETS = buckets();

    private static double[] buckets() {
        java.util.List<Double> b = new java.util.ArrayList<>();
        for (double v = 0.001; v < 30; v *= 1.25) {
            b.add(Double.parseDouble(String.format("%.4g", v)));
        }
        b.add(30.0);
        return b.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private volatile dev.sieve.PriorityLimiter limiter;

    /** Expose the limiter's current limit and in flight count as gauges. */
    void limiter(dev.sieve.PriorityLimiter l) {
        this.limiter = l;
    }

    private final Map<Priority, Map<Outcome, LongAdder>> outcomes = new EnumMap<>(Priority.class);
    private final Map<Priority, LongAdder> arrived = new EnumMap<>(Priority.class);
    private final Map<Priority, LongAdder[]> buckets = new EnumMap<>(Priority.class);
    private final Map<Priority, LongAdder> latencyNanosSum = new EnumMap<>(Priority.class);

    Metrics() {
        for (Priority p : Priority.values()) {
            Map<Outcome, LongAdder> m = new EnumMap<>(Outcome.class);
            for (Outcome o : Outcome.values()) {
                m.put(o, new LongAdder());
            }
            outcomes.put(p, m);
            LongAdder[] b = new LongAdder[BUCKETS.length + 1];
            for (int i = 0; i < b.length; i++) {
                b[i] = new LongAdder();
            }
            buckets.put(p, b);
            latencyNanosSum.put(p, new LongAdder());
            arrived.put(p, new LongAdder());
        }
    }

    /** A request reached the server, whatever happens to it next. */
    void arrived(Priority p) {
        arrived.get(p).increment();
    }

    void count(Priority p, Outcome o) {
        outcomes.get(p).get(o).increment();
    }

    /** Record the latency of a request that was served. */
    void served(Priority p, long nanos) {
        count(p, Outcome.OK);
        double seconds = nanos / 1e9;
        LongAdder[] b = buckets.get(p);
        int i = 0;
        while (i < BUCKETS.length && seconds > BUCKETS[i]) {
            i++;
        }
        b[i].increment(); // per-bucket counts; made cumulative when rendered
        latencyNanosSum.get(p).add(nanos);
    }

    String render() {
        StringBuilder sb = new StringBuilder();
        var os = (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        sb.append("# TYPE jvm_available_processors gauge\njvm_available_processors ")
                .append(os.getAvailableProcessors()).append('\n');
        sb.append("# TYPE jvm_process_cpu_load gauge\njvm_process_cpu_load ")
                .append(os.getProcessCpuLoad()).append('\n');
        sb.append("# TYPE jvm_system_cpu_load gauge\njvm_system_cpu_load ")
                .append(os.getCpuLoad()).append('\n');
        if (limiter != null) {
            sb.append("# TYPE sieve_concurrency_limit gauge\nsieve_concurrency_limit ")
                    .append(limiter.limit()).append('\n');
            sb.append("# TYPE sieve_concurrency_inflight gauge\nsieve_concurrency_inflight ")
                    .append(limiter.inflight()).append('\n');
        }
        sb.append("# TYPE sieve_requests_arrived_total counter\n");
        for (Priority p : Priority.values()) {
            sb.append("sieve_requests_arrived_total{tier=\"").append(p.id()).append("\"} ")
                    .append(arrived.get(p).sum()).append('\n');
        }
        sb.append("# TYPE sieve_requests_total counter\n");
        for (Priority p : Priority.values()) {
            for (Outcome o : Outcome.values()) {
                sb.append("sieve_requests_total{tier=\"").append(p.id()).append("\",outcome=\"")
                        .append(o.label).append("\"} ").append(outcomes.get(p).get(o).sum())
                        .append('\n');
            }
        }
        sb.append("# TYPE sieve_request_duration_seconds histogram\n");
        for (Priority p : Priority.values()) {
            LongAdder[] b = buckets.get(p);
            long cum = 0;
            for (int i = 0; i < BUCKETS.length; i++) {
                cum += b[i].sum();
                sb.append("sieve_request_duration_seconds_bucket{tier=\"").append(p.id())
                        .append("\",le=\"").append(BUCKETS[i]).append("\"} ").append(cum)
                        .append('\n');
            }
            cum += b[BUCKETS.length].sum();
            sb.append("sieve_request_duration_seconds_bucket{tier=\"").append(p.id())
                    .append("\",le=\"+Inf\"} ").append(cum).append('\n');
            sb.append("sieve_request_duration_seconds_sum{tier=\"").append(p.id()).append("\"} ")
                    .append(latencyNanosSum.get(p).sum() / 1e9).append('\n');
            sb.append("sieve_request_duration_seconds_count{tier=\"").append(p.id()).append("\"} ")
                    .append(cum).append('\n');
        }
        return sb.toString();
    }
}
