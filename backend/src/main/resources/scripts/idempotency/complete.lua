-- Stores the final response of a claimed key, only if this request still owns it (FLS-018).
-- KEYS[1] idem:{userId}:{key}
-- ARGV[1] owner token   ARGV[2] status   ARGV[3] content type   ARGV[4] body   ARGV[5] TTL (ms)
-- Returns 1 when stored, 0 when the claim was lost (expired and taken over).
if redis.call('HGET', KEYS[1], 'token') ~= ARGV[1] then
  return 0
end
redis.call('HSET', KEYS[1],
  'state', 'DONE', 'status', ARGV[2], 'contentType', ARGV[3], 'body', ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[5])
return 1
