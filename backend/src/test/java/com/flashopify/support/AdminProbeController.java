package com.flashopify.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint under the admin prefix, used to prove the ADMIN role rule. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminProbeController {

  @GetMapping("/_probe")
  public String probe() {
    return "ok";
  }
}
