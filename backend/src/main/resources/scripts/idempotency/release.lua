-- Drops a claim this request still owns, so the client may retry the same key (FLS-018).
-- KEYS[1] idem:{userId}:{key}   ARGV[1] owner token
if redis.call('HGET', KEYS[1], 'token') == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
