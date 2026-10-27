package com.flashopify.auth.controller;

import com.flashopify.auth.dto.AddressRequest;
import com.flashopify.auth.dto.AddressResponse;
import com.flashopify.auth.service.AddressService;
import com.flashopify.common.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/addresses")
@RequiredArgsConstructor
public class AddressController {

  private final AddressService addressService;

  @GetMapping
  public List<AddressResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return addressService.list(CurrentUser.id(jwt));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AddressResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddressRequest request) {
    return addressService.create(CurrentUser.id(jwt), request);
  }

  @PutMapping("/{addressId}")
  public AddressResponse update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long addressId,
      @Valid @RequestBody AddressRequest request) {
    return addressService.update(CurrentUser.id(jwt), addressId, request);
  }

  @DeleteMapping("/{addressId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long addressId) {
    addressService.delete(CurrentUser.id(jwt), addressId);
  }
}
