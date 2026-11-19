package com.flashopify.common.idempotency;

import com.flashopify.common.web.HandlerMethodLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Tells whether a request is routed to a controller method annotated with {@link Idempotent}. */
@Component
public class IdempotentEndpointResolver {

  private final HandlerMethodLookup lookup;

  public IdempotentEndpointResolver(HandlerMethodLookup lookup) {
    this.lookup = lookup;
  }

  public boolean isIdempotent(HttpServletRequest request) {
    return lookup.find(request).map(m -> m.hasMethodAnnotation(Idempotent.class)).orElse(false);
  }
}
