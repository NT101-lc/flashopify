package com.flashopify.common.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Tells whether a request is routed to a controller method annotated with {@link Idempotent}. */
@Component
public class IdempotentEndpointResolver {

  private final ObjectProvider<RequestMappingHandlerMapping> mapping;

  public IdempotentEndpointResolver(
      @Qualifier("requestMappingHandlerMapping")
          ObjectProvider<RequestMappingHandlerMapping> mapping) {
    this.mapping = mapping;
  }

  public boolean isIdempotent(HttpServletRequest request) {
    RequestMappingHandlerMapping m = mapping.getIfAvailable();
    if (m == null) {
      return false;
    }
    try {
      HandlerExecutionChain chain = m.getHandler(request);
      return chain != null
          && chain.getHandler() instanceof HandlerMethod method
          && method.hasMethodAnnotation(Idempotent.class);
    } catch (Exception e) {
      // No matching handler (404/405...): the dispatcher reports it, nothing to guard.
      return false;
    }
  }
}
