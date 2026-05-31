-- KEYS[1] = stock:seckillVoucher::{voucherId}
-- KEYS[2] = orderBought:seckillVoucher:{voucherId}::users
-- ARGV[1] = userId
redis.call("incrby", KEYS[1], 1)  --  库存自增1
redis.call("srem", KEYS[2], ARGV[1]) -- 移除set集合中指定元素
return 0