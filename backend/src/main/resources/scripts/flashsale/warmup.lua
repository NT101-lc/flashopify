-- Loads one flash-sale item into Redis (FLS-016). Idempotent:
--  * the stock is set only if absent, so a re-run never overwrites stock that is already selling;
--  * a deactivated item (cancelled/ended) is never reactivated.
-- KEYS[1] fs:stock:{id}   KEYS[2] fs:bought:{id} (unused)   KEYS[3] fs:meta:{id}
-- ARGV[1] totalStock  ARGV[2] startMs  ARGV[3] endMs  ARGV[4] limitPerUser  ARGV[5] expireAtMs
-- Returns the current stock.
redis.call('SET', KEYS[1], ARGV[1], 'NX')
if redis.call('HGET', KEYS[3], 'active') ~= '0' then
  redis.call('HSET', KEYS[3],
    'total', ARGV[1], 'start', ARGV[2], 'end', ARGV[3], 'limit', ARGV[4], 'active', '1')
end
redis.call('PEXPIREAT', KEYS[1], ARGV[5])
redis.call('PEXPIREAT', KEYS[3], ARGV[5])
return tonumber(redis.call('GET', KEYS[1]))
