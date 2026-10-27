package com.flashopify.auth.service;

import com.flashopify.auth.domain.UserDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Revokes refresh tokens only once the deletion has committed. */
@Component
@RequiredArgsConstructor
public class UserDeletedListener {

  private final TokenService tokenService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onUserDeleted(UserDeletedEvent event) {
    tokenService.revokeAll(event.userId());
  }
}
