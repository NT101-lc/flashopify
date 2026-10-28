import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { components } from './schema';
import { clearSession, getAccessToken, getRefreshToken, setSession, subscribe } from './tokenStore';

const session: components['schemas']['TokenResponse'] = {
  accessToken: 'A',
  refreshToken: 'R',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: {
    id: 1,
    email: 'a@b.c',
    fullName: 'A',
    role: 'CUSTOMER',
    createdAt: '2026-01-01T00:00:00Z',
  },
};

describe('tokenStore', () => {
  beforeEach(() => {
    clearSession();
  });

  it('TC-10 persists and removes the refresh token and notifies subscribers', () => {
    const listener = vi.fn();
    const unsubscribe = subscribe(listener);

    setSession(session);
    expect(getAccessToken()).toBe('A');
    expect(getRefreshToken()).toBe('R');
    expect(localStorage.getItem('flashopify.refreshToken')).toBe('R');
    expect(listener).toHaveBeenCalledTimes(1);

    clearSession();
    expect(getAccessToken()).toBeNull();
    expect(localStorage.getItem('flashopify.refreshToken')).toBeNull();
    expect(listener).toHaveBeenCalledTimes(2);

    unsubscribe();
    setSession(session);
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('survives unavailable localStorage', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(() => setSession(session)).not.toThrow();
    expect(getAccessToken()).toBe('A');
    spy.mockRestore();
  });
});
