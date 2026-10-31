package dev.sieve.spring;

import dev.sieve.Priority;

/**
 * Priority of the request being handled on this thread, so outgoing calls can carry it on. A
 * service may downgrade a priority but never upgrade it: an upgrade means the edge classified the
 * request wrongly and has to be fixed there.
 */
public final class SievePriorityContext {

    private static final ThreadLocal<Priority> CURRENT = new ThreadLocal<>();

    private SievePriorityContext() {}

    /** The current request's priority, or null outside a request. */
    public static Priority current() {
        return CURRENT.get();
    }

    /**
     * Lower the current priority. Asking for a higher or equal priority is ignored.
     *
     * @return the priority now in effect
     */
    public static Priority downgrade(Priority to) {
        Priority now = CURRENT.get();
        if (now != null && to.ordinal() > now.ordinal()) {
            CURRENT.set(to);
            return to;
        }
        return now;
    }

    static void set(Priority p) {
        CURRENT.set(p);
    }

    static void clear() {
        CURRENT.remove();
    }
}
