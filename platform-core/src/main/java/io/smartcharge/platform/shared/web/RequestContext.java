package io.smartcharge.platform.shared.web;

public final class RequestContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private RequestContext() { }

    static void set(String requestId, String sourceIp) {
        CURRENT.set(new State(requestId, sourceIp));
    }

    static void clear() {
        CURRENT.remove();
    }

    public static String requestId() {
        State state = CURRENT.get();
        return state == null ? null : state.requestId();
    }

    public static String sourceIp() {
        State state = CURRENT.get();
        return state == null ? null : state.sourceIp();
    }

    private record State(String requestId, String sourceIp) { }
}
