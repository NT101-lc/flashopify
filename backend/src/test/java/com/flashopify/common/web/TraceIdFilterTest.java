package com.flashopify.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

  private final TraceIdFilter filter = new TraceIdFilter();

  @Test
  void reusesWellFormedIncomingTraceIdAndClearsMdc() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TraceIdFilter.HEADER, "abc-12345");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] seenInChain = new String[1];

    filter.doFilter(
        request,
        response,
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            seenInChain[0] = MDC.get(TraceIdFilter.MDC_KEY);
          }
        });

    assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc-12345");
    assertThat(seenInChain[0]).isEqualTo("abc-12345");
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void generatesTraceIdWhenHeaderMissingOrMalformed() {
    assertThat(TraceIdFilter.resolveTraceId(null)).matches("[0-9a-f]{32}");
    assertThat(TraceIdFilter.resolveTraceId("bad id\r\ninjected")).matches("[0-9a-f]{32}");
  }
}
