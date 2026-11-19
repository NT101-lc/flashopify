-- Claims an Idempotency-Key (FLS-018).
-- KEYS[1] idem:{userId}:{key}
-- ARGV[1] request hash   ARGV[2] owner token   ARGV[3] processing TTL (ms)
-- Returns {'CLAIMED'} | {'CONFLICT'} | {'IN_PROGRESS'} | {'DONE', status, contentType, body}
local state = redis.call('HGET', KEYS[1], 'state')
if not state then
  redis.call('HSET', KEYS[1], 'state', 'PROCESSING', 'hash', ARGV[1], 'token', ARGV[2])
  redis.call('PEXPIRE', KEYS[1], ARGV[3])
  return {'CLAIMED'}
end
if redis.call('HGET', KEYS[1], 'hash') ~= ARGV[1] then
  return {'CONFLICT'}
end
if state == 'PROCESSING' then
  return {'IN_PROGRESS'}
end
local r = redis.call('HMGET', KEYS[1], 'status', 'contentType', 'body')
return {'DONE', r[1], r[2] or '', r[3] or ''}
