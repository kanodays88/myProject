package com.hmdp;

import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtil;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
public class RedisUtilConcurrencyTest {

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String TEST_KEY_PREFIX = "test:shop:";
    private static final long TEST_SHOP_ID = 1L;

    @BeforeEach
    public void setUp() {
        redisUtil.deleteLock(TEST_KEY_PREFIX + TEST_SHOP_ID);
        stringRedisTemplate.delete(TEST_KEY_PREFIX + TEST_SHOP_ID);
    }

    @Test
    public void testSetValueForRedis() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        String testValue = "testShopData";

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executorService.submit(() -> {
                try {
                    boolean result = redisUtil.setValueForRedis(TEST_KEY_PREFIX + threadNum, testValue, 10L, java.util.concurrent.TimeUnit.MINUTES);
                    if (result) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        System.out.println("========== setValueForRedis 测试结果 ==========");
        System.out.println("总线程数: " + threadCount);
        System.out.println("成功次数: " + successCount.get());
        System.out.println("失败次数: " + failCount.get());

        if (successCount.get() == threadCount) {
            System.out.println("✓ setValueForRedis 方法在高并发下正常运行");
        } else {
            System.out.println("✗ setValueForRedis 方法存在异常");
        }
    }

    @Test
    public void testGetValueTimeWithExpiration() throws InterruptedException {
        RedisData redisData = new RedisData();
        redisData.setData("testData");
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(2));
        String json = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(TEST_KEY_PREFIX + TEST_SHOP_ID, json);

        Thread.sleep(500);

        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    RedisData result = redisUtil.getValueTime(TEST_KEY_PREFIX, TEST_SHOP_ID, null);
                    if (result != null) {
                        successCount.incrementAndGet();
                        System.out.println("线程获取到缓存，过期时间: " + result.getExpireTime());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        Thread.sleep(3000);

        String afterJson = stringRedisTemplate.opsForValue().get(TEST_KEY_PREFIX + TEST_SHOP_ID);
        RedisData afterExpiration = null;
        if (afterJson != null && !afterJson.isEmpty()) {
            afterExpiration = JSONUtil.toBean(afterJson, RedisData.class);
        }

        System.out.println("\n========== getValueTime 逻辑过期测试结果 ==========");
        System.out.println("总线程数: " + threadCount);
        System.out.println("成功获取到缓存: " + successCount.get());
        System.out.println("过期后缓存数据: " + (afterExpiration != null ? afterExpiration.getData() : "null"));
        System.out.println("过期后过期时间: " + (afterExpiration != null ? afterExpiration.getExpireTime() : "null"));
        System.out.println("当前时间: " + LocalDateTime.now());

        if (afterExpiration != null && afterExpiration.getExpireTime() != null && afterExpiration.getExpireTime().isAfter(LocalDateTime.now())) {
            System.out.println("✓ 逻辑过期时间已更新，缓存重建成功");
        } else if (afterExpiration != null) {
            System.out.println("✗ 逻辑过期时间未更新");
        } else {
            System.out.println("缓存已过期或不存在");
        }

        executorService.shutdown();
    }

    @Test
    public void testHighConcurrencyGetValueTime() throws InterruptedException {
        RedisData redisData = new RedisData();
        redisData.setData("testData");
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(10));
        String json = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(TEST_KEY_PREFIX + TEST_SHOP_ID, json);

        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger getCacheCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    RedisData result = redisUtil.getValueTime(TEST_KEY_PREFIX, TEST_SHOP_ID, null);
                    if (result != null) {
                        getCacheCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        System.out.println("========== 高并发 getValueTime 测试结果 ==========");
        System.out.println("总线程数: " + threadCount);
        System.out.println("成功获取缓存: " + getCacheCount.get());

        if (getCacheCount.get() == threadCount) {
            System.out.println("✓ 高并发下 getValueTime 方法正常运行");
        } else {
            System.out.println("✗ 高并发下 getValueTime 方法存在问题");
        }
    }
}
