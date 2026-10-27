package com.flashopify.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashopify.common.exception.ErrorCode;
import com.flashopify.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes 401/403 raised inside the security filter chain (before any controller) as {@link
 * ErrorResponse}.
 */
@Component
@RequiredArgsConstructor
public class JsonErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException {
    write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Authentication required");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    write(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied");
  }

  private void write(HttpServletResponse response, HttpStatus status, ErrorCode code, String msg)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, msg));
  }
}
