package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class RedisUtil {



    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //创建线程池为10个线程
    private final ExecutorService CACHE_REFRESH_POOL = Executors.newFixedThreadPool(10);

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
    public <T> boolean setValueForRedis(String key, T val, LocalDateTime localDateTime){
        //初始化封装类型
        RedisData<T> redisData = new RedisData<>();
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
            //不管过没过期都直接返回bean
            //如果bean是过期的就开启新线程创建缓存
            if(LocalDateTime.now().compareTo(bean.getExpireTime()) > 0){
                //从线程池调用线程执行方法
                CACHE_REFRESH_POOL.submit(()->{
                    //获取分布式锁的情况
                    boolean lockStatus = false;
                    try{
                        //获取分布式锁
                        lockStatus = tryLock(lockName + id,1,5);
                        if(lockStatus == false){
                            //锁获取失败，有其他线程在创建缓存
                            log.error("锁获取失败，有其他线程在创建缓存");
                            return;
                        }
                        //获取锁成功，查询数据并更新缓存
                        T t = tMapper.selectById((Serializable) id);
                        boolean cacheStatus = setValueForRedis(keyPrefix + id, t, LocalDateTime.now().plusMinutes(5));
                        if(cacheStatus == false){
                            log.error("缓存创建失败");
                            return;
                        }
                    }catch (Exception e){
                        e.printStackTrace();
                    }finally {
                        if(lockStatus == true){
                            deleteLock(lockName+id);
                        }
                    }
                });
            }
            return bean;
        }
        return null;
    }

    //shutdown拒绝新任务，等待已提交任务执行  shutdownNow强制关闭  awaitTermination阻塞等待线程池关闭，关闭完成返回true，超时返回false

    // 4. 优雅关闭线程池（Spring销毁时执行）
    @PreDestroy
    public void destroyThreadPool() {
        if (CACHE_REFRESH_POOL == null || CACHE_REFRESH_POOL.isShutdown()) {
            return;
        }

        log.info("开始关闭缓存重建线程池...");
        // 第一步：发起关闭请求（拒绝新任务，等待已提交任务执行）
        CACHE_REFRESH_POOL.shutdown();

        try {
            // 第二步：阻塞等待，最多等10秒（超时则强制关闭）
            // 超时时间根据你的业务调整：缓存重建一般5秒内完成，设10秒足够
            if (!CACHE_REFRESH_POOL.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("线程池10秒内未关闭完成，强制中断剩余任务");
                // 第三步：超时未关闭，强制中断（兜底）
                List<Runnable> remainingTasks = CACHE_REFRESH_POOL.shutdownNow();
                log.warn("强制关闭线程池，未执行的任务数：{}", remainingTasks.size());
            } else {
                log.info("线程池优雅关闭完成");
            }
        } catch (InterruptedException e) {
            // 等待过程中被中断，再次强制关闭
            log.error("线程池关闭等待被中断，强制关闭", e);
            CACHE_REFRESH_POOL.shutdownNow();
            // 恢复中断状态（线程规范）
            Thread.currentThread().interrupt();
        }
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
