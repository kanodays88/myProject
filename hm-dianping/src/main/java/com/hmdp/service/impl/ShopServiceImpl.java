package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisUtil redisUtil;
    @Override
    //该注解会形成cacheNames::key的键，然后查询该键的值，如果有直接返回不执行主方法，如果没有执行主方法并将其返回值作为缓存键的值
//    @Cacheable(cacheNames = "cache:shop",key = "#id")
    public Result queryShopById(Long id) throws InterruptedException {
        String cacheName = "cache:shop::";
        Shop shop = redisUtil.getValueTTL(cacheName, id, Shop.class, shopMapper);
        return Result.ok(shop);
    }

    @Override
    //删除缓存操作，在方法执行后执行，cacheNames指定缓存的前缀，key指定特定缓存，allEntries=true删除掉属于该前缀的所有缓存
    //@CacheEvict(cacheNames = "cache:shop",key = "#shop.getId()")//使用该注解会导致当缓存删除失败时数据库无法回滚,产生数据不一致
    @Transactional
    public int updateShop(Shop shop) {
        //修改数据库数据
        int rows = shopMapper.updateById(shop);
        if(rows <= 0){
            throw new RuntimeException("数据库更新失败：updateShop");
        }
        //删除缓存
        stringRedisTemplate.delete("cache:shop::"+shop.getId());
        return rows;
    }
}
