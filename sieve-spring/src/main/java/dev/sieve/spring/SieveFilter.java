package dev.sieve.spring;

import dev.sieve.Admission;
import dev.sieve.Priority;
import dev.sieve.Sieve;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Function;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pre-processing filter: classifies a request from a header (never the body, so shed requests
 * cost almost nothing), asks Sieve whether to run it, and answers 503 with Retry-After if not.
 */
public class SieveFilter extends OncePerRequestFilter {

    public static final String SHED_REASON_HEADER = "X-Sieve-Shed";

    private final Sieve sieve;
    private final Function<HttpServletRequest, Priority> resolver;

    public SieveFilter(Sieve sieve, Function<HttpServletRequest, Priority> resolver) {
        this.sieve = sieve;
        this.resolver = resolver;
    }

    /** Resolve the priority from a request header, falling back to {@code fallback}. */
    public static Function<HttpServletRequest, Priority> fromHeader(
            String header, Priority fallback) {
        return req -> Priority.parse(req.getHeader(header)).orElse(fallback);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Priority priority = resolver.apply(request);
        switch (sieve.admit(priority)) {
            case Admission.Shed shed -> {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setHeader("Retry-After", Long.toString(shed.retryAfter().toSeconds()));
                response.setHeader(SHED_REASON_HEADER, shed.reason().name().toLowerCase());
            }
            case Admission.Admitted admitted -> {
                SievePriorityContext.set(priority);
                boolean finished = false;
                try {
                    chain.doFilter(request, response);
                    finished = true;
                } finally {
                    SievePriorityContext.clear();
                    int status = response.getStatus();
                    if (!finished || status >= 500) {
                        admitted.permit().dropped();
                    } else if (status >= 400) {
                        admitted.permit().ignore();
                    } else {
                        admitted.permit().success();
                    }
                }
            }
        }
    }
}
