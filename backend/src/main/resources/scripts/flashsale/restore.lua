-- Gives units of a cancelled/failed/expired flash-sale order back to the pool (FLS-017).
-- KEYS[1] fs:stock:{id}   KEYS[2] fs:bought:{id}   KEYS[3] fs:meta:{id}
-- ARGV[1] userId   ARGV[2] quantity (>= 1)
-- Returns:
--   OK          stock += qty, the user's bought -= qty (the user may buy again)
--   NOT_HELD    the user does not hold qty units, or stock would exceed totalStock; no change
--   NOT_ACTIVE  item closed (ENDED/CANCELLED) or keys gone; no change, the caller must release
--               the MySQL reservation itself
if redis.call('HGET', KEYS[3], 'active') ~= '1' then
  return 'NOT_ACTIVE'
end
local qty = tonumber(ARGV[2])
local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if bought < qty then
  return 'NOT_HELD'
end
local total = tonumber(redis.call('HGET', KEYS[3], 'total'))
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock + qty > total then
  return 'NOT_HELD'
end
redis.call('INCRBY', KEYS[1], qty)
if bought == qty then
  redis.call('HDEL', KEYS[2], ARGV[1])
else
  redis.call('HINCRBY', KEYS[2], ARGV[1], -qty)
end
return 'OK'
