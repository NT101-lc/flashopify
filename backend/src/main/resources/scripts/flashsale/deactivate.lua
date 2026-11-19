-- Stops all further purchases and restores of one item and returns its frozen stock (FLS-016).
-- Idempotent: calling it again returns the same number, because nothing can change the stock of
-- an inactive item.
-- KEYS[1] fs:stock:{id}   KEYS[2] fs:bought:{id} (unused)   KEYS[3] fs:meta:{id}
-- Returns the remaining stock, or -1 when the item was never loaded or its keys are gone.
if redis.call('EXISTS', KEYS[3]) == 0 then
  return -1
end
redis.call('HSET', KEYS[3], 'active', '0')
local stock = redis.call('GET', KEYS[1])
if not stock then
  return -1
end
return tonumber(stock)
