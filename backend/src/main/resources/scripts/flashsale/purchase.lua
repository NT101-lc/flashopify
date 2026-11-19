-- Atomic flash-sale purchase (FLS-017, ADR-0002): window -> per-user limit -> stock -> deduct.
-- Uses the Redis server clock, so every app instance sees the same window.
-- KEYS[1] fs:stock:{id}   KEYS[2] fs:bought:{id}   KEYS[3] fs:meta:{id}
-- ARGV[1] userId   ARGV[2] quantity (>= 1)
-- Returns OK | NOT_ACTIVE | LIMIT_EXCEEDED | SOLD_OUT; nothing changes unless OK.
local meta = redis.call('HMGET', KEYS[3], 'active', 'start', 'end', 'limit')
if meta[1] ~= '1' then
  return 'NOT_ACTIVE'
end
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if now < tonumber(meta[2]) or now >= tonumber(meta[3]) then
  return 'NOT_ACTIVE'
end
local qty = tonumber(ARGV[2])
local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if bought + qty > tonumber(meta[4]) then
  return 'LIMIT_EXCEEDED'
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock < qty then
  return 'SOLD_OUT'
end
redis.call('DECRBY', KEYS[1], qty)
redis.call('HINCRBY', KEYS[2], ARGV[1], qty)
if redis.call('PTTL', KEYS[2]) == -1 then
  -- The bought hash is created here; give it the same lifetime as the item.
  local expireAt = redis.call('PEXPIRETIME', KEYS[3])
  if expireAt > 0 then
    redis.call('PEXPIREAT', KEYS[2], expireAt)
  end
end
return 'OK'
