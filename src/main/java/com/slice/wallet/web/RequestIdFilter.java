package com.slice.wallet.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request a correlation id, and logs one line per request.
 *
 * <p>An inbound {@code X-Request-Id} is <b>echoed, not replaced</b>: when the caller is another
 * service, that id is how a trace spanning both of us joins up. It goes into the SLF4J MDC so
 * every log line emitted while handling the request carries it, into the response header, and
 * into every error body.
 *
 * <p>Ordered first, ahead of Spring Security, so that a 401 produced by the filter chain is
 * logged and correlated like everything else.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = "wallet.requestId";

    private static final Logger log = LoggerFactory.getLogger("access");

    /** The current request's id, for code that is not holding the request itself. */
    public static String currentRequestId() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "unknown" : id;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId = (inbound == null || inbound.isBlank())
                ? UUID.randomUUID().toString()
                : inbound;

        MDC.put(MDC_KEY, requestId);
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);

        long startedNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long micros = (System.nanoTime() - startedNanos) / 1_000L;
            log.info("{} {} -> {} ({} us)",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), micros);
            // ALWAYS clear: worker threads are pooled, so a leftover MDC entry would attach this
            // request's id to somebody else's log lines for the rest of the process's life.
            MDC.remove(MDC_KEY);
        }
    }
}
