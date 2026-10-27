package com.flashopify.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a trace id to every request, puts it in the MDC (key {@value #MDC_KEY}) and echoes it in
 * the {@value #HEADER} response header. A well-formed incoming {@value #HEADER} is reused.
 *
 * <p>Runs before Spring Security so 401/403 bodies carry the trace id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Trace-Id";
  public static final String MDC_KEY = "traceId";

  private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = resolveTraceId(request.getHeader(HEADER));
    MDC.put(MDC_KEY, traceId);
    response.setHeader(HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /** Trace id of the current request, or a fresh one when called outside a request. */
  public static String currentTraceId() {
    String traceId = MDC.get(MDC_KEY);
    return traceId != null ? traceId : newTraceId();
  }

  static String resolveTraceId(String incoming) {
    if (incoming != null && VALID_TRACE_ID.matcher(incoming).matches()) {
      return incoming;
    }
    return newTraceId();
  }

  private static String newTraceId() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}
