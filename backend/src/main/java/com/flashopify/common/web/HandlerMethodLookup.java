package com.flashopify.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Finds the controller method a request is routed to, for servlet filters that act on method
 * annotations ({@code @Idempotent}, {@code @RateLimited}). The result is cached on the request, so
 * several filters cost one lookup.
 */
@Component
public class HandlerMethodLookup {

  private static final String ATTRIBUTE = HandlerMethodLookup.class.getName() + ".handler";
  private static final Object NONE = new Object();

  private final ObjectProvider<RequestMappingHandlerMapping> mapping;

  public HandlerMethodLookup(
      @Qualifier("requestMappingHandlerMapping")
          ObjectProvider<RequestMappingHandlerMapping> mapping) {
    this.mapping = mapping;
  }

  public Optional<HandlerMethod> find(HttpServletRequest request) {
    Object cached = request.getAttribute(ATTRIBUTE);
    if (cached == null) {
      HandlerMethod found = resolve(request);
      cached = found == null ? NONE : found;
      request.setAttribute(ATTRIBUTE, cached);
    }
    return cached instanceof HandlerMethod method ? Optional.of(method) : Optional.empty();
  }

  private HandlerMethod resolve(HttpServletRequest request) {
    RequestMappingHandlerMapping m = mapping.getIfAvailable();
    if (m == null) {
      return null;
    }
    try {
      HandlerExecutionChain chain = m.getHandler(request);
      return chain != null && chain.getHandler() instanceof HandlerMethod method ? method : null;
    } catch (Exception e) {
      // No matching handler (404/405...): the dispatcher reports it.
      return null;
    }
  }
}
