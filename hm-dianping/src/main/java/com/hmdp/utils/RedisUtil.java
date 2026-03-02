package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class RedisUtil {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //lua脚本的加载类                        返回类型
    private static final DefaultRedisScript<Long> REDIS_SCRIPT;
    static {
        //初始化
        REDIS_SCRIPT = new DefaultRedisScript<>();
        //指定lua脚本                       将lua脚本包装成Resource类型
        REDIS_SCRIPT.setLocation(new ClassPathResource("Lua/unLock.lua"));
        //指定返回类型
        REDIS_SCRIPT.setResultType(Long.class);
    }




    public <T> boolean tryLock(String key,T value,long time){
        //setIfAbsent创建键值对，如果存在则创建并返回true,否则不创建返回false
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, value+"", time, TimeUnit.SECONDS);
//        return aBoolean.booleanValue();这种方法无法避免当aBoolean为null产生报错
        return Boolean.TRUE.equals(aBoolean);//直接用其与TRUE进行对比
    }

    //普通缓存删除
    public void deleteLock(String key){
        stringRedisTemplate.delete(key);
    }
    /**
     * 分布式锁对应锁的缓存删除，主要实现（判断该线程和该锁是不是对应关系，对应可删）
     * @param key 锁名
     * @param argv 锁对应的值（线程的唯一标识加UUID)
     */
    public void deleteLock(String key,String argv){
        //执行lua脚本
        Long execute = stringRedisTemplate.execute(REDIS_SCRIPT, Collections.singletonList(key), argv);
    }

    //往redis添加缓存，具有TTL过期时间
    public boolean setValueForRedis(String key, Object val, Long time, TimeUnit timeUnit){
        //将val转成json,如果val为空就存入空缓存
        String json = "";
        if(val != null){
            json = JSONUtil.toJsonStr(val);
        }
        try{
            //如果抛出异常就表示缓存失败
            stringRedisTemplate.opsForValue().set(key,json,time,timeUnit);
        }catch (Exception e){
            log.info("缓存创建时错误");
            return false;
        }
        return true;
    }

    //往redis添加缓存，逻辑过期时间
    public boolean setValueForRedis(String key, Object val, LocalDateTime localDateTime){
        //初始化封装类型
        RedisData redisData = new RedisData();
        redisData.setData(val);
        redisData.setExpireTime(localDateTime);
        //将redisData转json,如果val为空就存入空缓存
        String json = "";
        if(val != null){
            json = JSONUtil.toJsonStr(redisData);
        }
        try{
            //报错则表示缓存创建失败
            if(json.equals("")) stringRedisTemplate.opsForValue().set(key,json,1L,TimeUnit.MINUTES);
            else stringRedisTemplate.opsForValue().set(key,json);
        }catch(Exception e){
            log.info("缓存创建时错误");
            return false;
        }
        return true;
    }

    /**
     * 根据key查找缓存，用缓存空值解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param classType
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T,ID> T getValueTTL(String keyPrefix, ID id, Class<T> classType, BaseMapper<T> tMapper){
        //初始化锁名
        String lockName = "lock:"+keyPrefix;
        //缓存空值用TTL不用逻辑过期，需要排除逻辑过期
        if(classType == RedisData.class){
            log.info("缓存空值用TTL不用逻辑过期");
            return null;
        }
        //查询缓存是否存在
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if(json != null){
            //如果不为空，直接返回
            if(json.equals("")) return null;
            T bean = JSONUtil.toBean(json, classType);
            return bean;
        }
        boolean lockStatus = false;//存储获取互斥锁的状态
        T t = null;//获取数据库查询结果
        //不存在
        try{
            //尝试获取互斥锁
            lockStatus = tryLock(lockName + id,1,5);
            //获取锁失败
            if(lockStatus == false) {
                for (int i = 0; i < 5; i++) {
                    //休眠50毫秒
                    Thread.sleep(50);
                    //再访问缓存存不存在
                    json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
                    if (json != null) {
                        //如果不为空，直接返回
                        if(json.equals("")) return null;
                        T bean = JSONUtil.toBean(json, classType);
                        return bean;
                    }
                }
                log.info("访问缓存超时，请重试");
                return null;
            }
            //获取锁成功
            t = tMapper.selectById((Serializable) id);
            //存入缓存
            setValueForRedis(keyPrefix+id,t,1L,TimeUnit.MINUTES);
        }catch (Exception e){
            e.printStackTrace();
        } finally {
            //如果获取锁成功就删除锁
            if(lockStatus == true) {
                deleteLock(lockName+id);
            }
        }
        return t;
    }

    /**
     * 根据key查找缓存，用逻辑过期解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T,ID> RedisData getValueTime(String keyPrefix,ID id,BaseMapper<T> tMapper){
        //初始化锁名
        String lockName = "lock:"+keyPrefix;
        //取缓存
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        RedisData bean = null;//存储获取到的缓存
        if(json != null){
            //如果存在，且不为空
            if(json.equals("")) return null;
            bean = JSONUtil.toBean(json, RedisData.class);
            //如果没过期，直接返回
            if(LocalDateTime.now().compareTo(bean.getExpireTime()) <= 0){
                return bean;
            }
        }

        boolean lockStatus = false;//存储获取互斥锁的状态
        T t = null;//获取数据库查询结果
        //过期
        try{
            //尝试获取互斥锁
            lockStatus = tryLock(lockName + id,1,5);
            //获取锁失败
            if(lockStatus == false) {
                //直接返回旧数据或者空数据
                return bean;
            }
            //获取锁成功
            t = tMapper.selectById((Serializable) id);
            //存入缓存
            setValueForRedis(keyPrefix+id,t,LocalDateTime.now().plusMinutes(60));
        }catch (Exception e){
            e.printStackTrace();
        } finally {
            //如果获取锁成功就删除锁
            if(lockStatus == true) {
                deleteLock(lockName+id);
            }
        }
        RedisData redisData = new RedisData();
        redisData.setData(t);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(60));
        return redisData;
    }
    //时间戳的起点
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //移位的位数
    private static final int COUNT_BITS = 32;

    public long nextId(String keyPrefix){
        //获取时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);//时间戳终点
        long timesTamp = nowSecond-BEGIN_TIMESTAMP;//时间戳


        //获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增长，主要是记录id个数,返回对应个数
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + "::" + date);//该方法会对key对应的数值自增，没有则舒适化为0再自增

        //拼接id返回
        return timesTamp << COUNT_BITS | count;


    }
}
