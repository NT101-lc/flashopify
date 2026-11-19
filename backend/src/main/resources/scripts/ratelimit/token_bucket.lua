-- Token buckets for one request (FLS-019). All KEYS belong to one policy and share its hash tag,
-- so they are checked together: a token is taken from every bucket, or from none.
-- KEYS[i]          rl:{policy}:<dimension>:<subject>
-- ARGV[2i-1]       capacity of bucket i
-- ARGV[2i]         refill period of bucket i (ms per token)
-- Returns {1, 0} when allowed, {0, retryAfterMs} when any bucket is empty.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local levels = {}
local wait = 0
for i = 1, #KEYS do
  local capacity = tonumber(ARGV[2 * i - 1])
  local period = tonumber(ARGV[2 * i])
  local b = redis.call('HMGET', KEYS[i], 'tokens', 'ts')
  local tokens = tonumber(b[1])
  local ts = tonumber(b[2])
  if tokens == nil or ts == nil then
    tokens = capacity
    ts = now
  end
  tokens = math.min(capacity, tokens + math.max(0, now - ts) / period)
  levels[i] = tokens
  if tokens < 1 then
    wait = math.max(wait, math.ceil((1 - tokens) * period))
  end
end
if wait > 0 then
  return {0, wait}
end
for i = 1, #KEYS do
  local capacity = tonumber(ARGV[2 * i - 1])
  local period = tonumber(ARGV[2 * i])
  redis.call('HSET', KEYS[i], 'tokens', tostring(levels[i] - 1), 'ts', tostring(now))
  -- Gone once it would be full again: an absent bucket reads as full.
  redis.call('PEXPIRE', KEYS[i], math.ceil(capacity * period) + 1000)
end
return {1, 0}
