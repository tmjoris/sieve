package dev.sieve.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.netflix.concurrency.limits.limit.FixedLimit;
import dev.sieve.Priority;
import dev.sieve.PriorityLimiter;
import dev.sieve.Sieve;
import dev.sieve.TierShares;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SieveFilterTest {

    private static Sieve sieve(int limit) {
        return Sieve.builder(new PriorityLimiter(FixedLimit.of(limit), TierShares.criticalFirst()))
                .build();
    }

    private static SieveFilter filter(Sieve s) {
        return new SieveFilter(s, SieveFilter.fromHeader(Priority.HEADER, Priority.BEST_EFFORT));
    }

    private static MockHttpServletRequest request(String priority) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/work");
        if (priority != null) {
            r.addHeader(Priority.HEADER, priority);
        }
        return r;
    }

    @Test
    void admittedRequestReachesTheChain() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<Priority> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(SievePriorityContext.current());
        filter(sieve(10)).doFilter(request("critical"), res, chain);
        assertEquals(200, res.getStatus());
        assertEquals(Priority.CRITICAL, seen.get());
        assertNull(SievePriorityContext.current(), "context must be cleared after the request");
    }

    @Test
    void missingOrUnknownHeaderUsesTheDefault() throws Exception {
        AtomicReference<Priority> seen = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> seen.set(SievePriorityContext.current());
        filter(sieve(10)).doFilter(request(null), new MockHttpServletResponse(), chain);
        assertEquals(Priority.BEST_EFFORT, seen.get());
        filter(sieve(10)).doFilter(request("nonsense"), new MockHttpServletResponse(), chain);
        assertEquals(Priority.BEST_EFFORT, seen.get());
    }

    @Test
    void shedRequestGets503WithRetryAfterAndNeverReachesTheChain() throws Exception {
        Sieve s = sieve(1);
        SieveFilter f = filter(s);
        // Hold the only slot with a request that has not finished.
        FilterChain blocking = (rq, rs) -> {
            MockHttpServletResponse res2 = new MockHttpServletResponse();
            boolean[] reached = {false};
            f.doFilter(request("critical"), res2, (a, b) -> reached[0] = true);
            assertEquals(503, res2.getStatus());
            assertEquals("0", res2.getHeader("Retry-After"));
            assertEquals("concurrency_limit", res2.getHeader(SieveFilter.SHED_REASON_HEADER));
            assertEquals(false, reached[0]);
        };
        f.doFilter(request("critical"), new MockHttpServletResponse(), blocking);
    }

    @Test
    void lowTierGetsLongerRetryAfter() throws Exception {
        // A zero-share tier still owns one slot, so hold that slot and ask for another.
        Sieve s = sieve(1);
        SieveFilter f = filter(s);
        FilterChain blocking = (rq, rs) -> {
            MockHttpServletResponse res2 = new MockHttpServletResponse();
            f.doFilter(request("bulk"), res2, (a, b) -> { });
            assertEquals(503, res2.getStatus());
            assertEquals("10", res2.getHeader("Retry-After"));
        };
        f.doFilter(request("bulk"), new MockHttpServletResponse(), blocking);
    }

    @Test
    void exceptionInHandlerReleasesThePermitAndPropagates() {
        Sieve s = sieve(1);
        SieveFilter f = filter(s);
        assertThrows(RuntimeException.class, () -> f.doFilter(request("critical"),
                new MockHttpServletResponse(), (a, b) -> { throw new RuntimeException("boom"); }));
        // The slot must be free again.
        MockHttpServletResponse res = new MockHttpServletResponse();
        try {
            f.doFilter(request("critical"), res, (a, b) -> { });
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(200, res.getStatus());
    }

    @Test
    void priorityCanBeDowngradedButNotUpgraded() throws Exception {
        AtomicReference<Priority> after = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> {
            SievePriorityContext.downgrade(Priority.BULK);
            SievePriorityContext.downgrade(Priority.CRITICAL); // ignored
            after.set(SievePriorityContext.current());
        };
        filter(sieve(10)).doFilter(request("degraded"), new MockHttpServletResponse(), chain);
        assertEquals(Priority.BULK, after.get());
    }
}
