
-- 获取库存信息
local stock = redis.call("get",KEYS[1])
-- 获取已购用户表中是否存在该用户
local userStatus = redis.call("sismember",KEYS[2],ARVG[1])

if(tonumber(stock) <= 0) then
--    没有库存了
    return 1
end
if(userStatus == 1) then
--    该用户已下单
    return 2
end

-- 扣减库存
redis.call("incrby",KEYS[1],-1)
-- 将用户添加到已购用户表
redis.call("sadd",KEYS[2],ARVG[1])

return 0


