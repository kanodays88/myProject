

--逆天bug,我自己配的stringRedisTemplater是json序列化字符串，正确的是字符串200  我的由于是json序列化所以是字符串”200",自带双引号导致tonumber失败



-- 获取库存信息
local stock = redis.call("get", KEYS[1])
if stock == false or stock == nil then return 1 end

-- 如果字符串以双引号开头和结尾，则去掉它们
local clean_stock = stock
if string.sub(clean_stock, 1, 1) == '"' and string.sub(clean_stock, -1) == '"' then
    clean_stock = string.sub(clean_stock, 2, -2)
end

-- 获取已购用户表中是否存在该用户
local userStatus = redis.call("sismember",KEYS[2],ARGV[1])

if(tonumber(clean_stock)<= 0) then
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
redis.call("sadd",KEYS[2],ARGV[1])
return 0


