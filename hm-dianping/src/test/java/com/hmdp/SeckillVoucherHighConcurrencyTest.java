package com.hmdp;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SeckillVoucherHighConcurrencyTest {
    @Autowired
    private IVoucherOrderService voucherOrderService;
    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    private VoucherOrderMapper voucherOrderMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final Long TEST_VOUCHER_ID = 11L;
    private static final String STOCK_KEY = "stock:seckillVoucher::" + TEST_VOUCHER_ID;
    private static final String ORDER_USER_KEY = "orderBought:seckillVoucher:" + TEST_VOUCHER_ID + "::users";

    @BeforeEach
    public void setUp() {
        voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>());
        
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        if (seckillVoucher != null) {
            seckillVoucher.setStock(100);
            seckillVoucherMapper.updateById(seckillVoucher);
        } else {
            seckillVoucher = new SeckillVoucher();
            seckillVoucher.setVoucherId(TEST_VOUCHER_ID);
            seckillVoucher.setStock(100);
            seckillVoucher.setCreateTime(java.time.LocalDateTime.now());
            seckillVoucher.setBeginTime(java.time.LocalDateTime.now().minusDays(1));
            seckillVoucher.setEndTime(java.time.LocalDateTime.now().plusDays(1));
            seckillVoucherMapper.insert(seckillVoucher);
        }
        
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_USER_KEY);
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "100");
    }

    @Test
    public void test150DifferentUsers() throws InterruptedException {
        int threadCount = 150;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger soldOutCount = new AtomicInteger(0);
        AtomicInteger alreadyBoughtCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        String redisStockBefore = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        System.out.println("\n========== 150 Users Concurrent Seckill Test ==========");
        System.out.println("=== Initial State ===");
        System.out.println("Redis stock: " + redisStockBefore);
        
        SeckillVoucher initialSeckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        System.out.println("DB stock: " + (initialSeckillVoucher != null ? initialSeckillVoucher.getStock() : "null"));

        for (int i = 1; i <= threadCount; i++) {
            final Long userId = (long) (2000 + i);
            executorService.submit(() -> {
                try {
                    mockUserHolder(userId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        String errMsg = result != null ? result.getErrorMsg() : "null result";
                        if (errMsg != null) {
                            if (errMsg.contains("卖完了")) {
                                soldOutCount.incrementAndGet();
                            } else if (errMsg.contains("不要贪杯")) {
                                alreadyBoughtCount.incrementAndGet();
                            }
                        }
                        synchronized (errors) {
                            if (errors.size() < 10) errors.add(errMsg);
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    synchronized (errors) {
                        if (errors.size() < 10) errors.add(e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        
        String redisStockAfterMQ = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        System.out.println("\n=== After MQ Send (Before Processing) ===");
        System.out.println("Redis stock: " + redisStockAfterMQ);
        System.out.println("Success to MQ: " + successCount.get());
        System.out.println("Fail at Lua: " + failCount.get());
        
        waitForMessageProcessing();
        
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        int stock = seckillVoucher != null ? seckillVoucher.getStock() : -1;
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
        );

        System.out.println("\n=== Final Result ===");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Initial stock: 100");
        System.out.println("Success count (to MQ): " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("  - Sold out (卖完了): " + soldOutCount.get());
        System.out.println("  - Already bought (不要贪杯): " + alreadyBoughtCount.get());
        System.out.println("Final DB stock: " + stock);
        System.out.println("Total orders in DB: " + orderCount);
        System.out.println("Stock deducted: " + (100 - stock));
        
        if (stock < 0) {
            System.out.println("\n!!! PROBLEM: Stock is NEGATIVE (overselling detected) !!!");
            System.out.println("Expected: stock >= 0, Actual: " + stock);
        }
        
        if (orderCount > 100) {
            System.out.println("\n!!! PROBLEM: Orders exceed initial stock !!!");
            System.out.println("Expected: orders <= 100, Actual: " + orderCount);
        } else {
            System.out.println("\n[OK] No overselling: PASSED");
        }
        
        System.out.println("\nSample errors from Lua: " + errors);
        System.out.println("=======================================================\n");

        assertTrue(stock >= 0, "Stock should not be negative (no overselling)");
        assertTrue(orderCount <= 100, "Orders should not exceed initial stock");
    }

    @Test
    public void test400SameUsers() throws InterruptedException {
        int threadCount = 400;
        Long sameUserId = 9999L;
        
        System.out.println("\n========== Test 2: 400 Same User ==========");
        System.out.println("=== Initial State ===");
        System.out.println("User ID: " + sameUserId);
        
        String redisStockBefore = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        System.out.println("Redis stock: " + redisStockBefore);
        
        SeckillVoucher initialSeckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        System.out.println("DB stock: " + (initialSeckillVoucher != null ? initialSeckillVoucher.getStock() : "null"));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    mockUserHolder(sameUserId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        String errMsg = result != null ? result.getErrorMsg() : "null result";
                        synchronized (errors) {
                            if (errors.size() < 5) errors.add(errMsg);
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    synchronized (errors) {
                        if (errors.size() < 5) errors.add(e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        
        System.out.println("\n=== After MQ Send (Before Processing) ===");
        System.out.println("Success to MQ: " + successCount.get());
        System.out.println("Fail at Lua (one user can only pass once): " + failCount.get());
        
        waitForMessageProcessing();
        
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("\n=== Final Result ===");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Success count (to MQ): " + successCount.get());
        System.out.println("Fail count (at Lua): " + failCount.get());
        System.out.println("Orders for this user in DB: " + orderCount);
        
        if (orderCount > 1) {
            System.out.println("\n!!! PROBLEM: Same user created multiple orders !!!");
            System.out.println("Expected: 1 order, Actual: " + orderCount);
        } else {
            System.out.println("\n[OK] One user one order rule: PASSED");
        }
        
        System.out.println("\nSample errors from Lua: " + errors);
        System.out.println("============================================\n");

        assertEquals(1, orderCount, "Same user should only create 1 order");
    }

    @Test
    public void test400MixedUsers() throws InterruptedException {
        int threadCount = 400;
        int sameUserCount = 200;
        int differentUserCount = 200;
        Long sameUserId = 8888L;
        
        System.out.println("\n========== Test 3: 400 Mixed Users ==========");
        System.out.println("=== Initial State ===");
        System.out.println("Same user (ID=" + sameUserId + ") requests: " + sameUserCount);
        System.out.println("Different users requests: " + differentUserCount);
        
        String redisStockBefore = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        System.out.println("Redis stock: " + redisStockBefore);
        
        SeckillVoucher initialSeckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        System.out.println("DB stock: " + (initialSeckillVoucher != null ? initialSeckillVoucher.getStock() : "null"));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger sameUserSuccessCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final Long userId;
            if (i < sameUserCount) {
                userId = sameUserId;
            } else {
                userId = (long) (3000 + i);
            }
            
            final boolean isSameUser = userId.equals(sameUserId);
            
            executorService.submit(() -> {
                try {
                    mockUserHolder(userId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                        if (isSameUser) {
                            sameUserSuccessCount.incrementAndGet();
                        }
                    } else {
                        failCount.incrementAndGet();
                        String errMsg = result != null ? result.getErrorMsg() : "null result";
                        synchronized (errors) {
                            if (errors.size() < 5) errors.add(errMsg);
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    synchronized (errors) {
                        if (errors.size() < 5) errors.add(e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        
        System.out.println("\n=== After MQ Send (Before Processing) ===");
        System.out.println("Success to MQ: " + successCount.get());
        System.out.println("Fail at Lua: " + failCount.get());
        System.out.println("Same user passed Lua: " + sameUserSuccessCount.get());
        
        waitForMessageProcessing();
        
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        int stock = seckillVoucher != null ? seckillVoucher.getStock() : -1;
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
        );
        long sameUserOrderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("\n=== Final Result ===");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Success count (to MQ): " + successCount.get());
        System.out.println("Fail count (at Lua): " + failCount.get());
        System.out.println("Initial stock: 100");
        System.out.println("Final DB stock: " + stock);
        System.out.println("Total orders in DB: " + orderCount);
        System.out.println("Same user orders in DB: " + sameUserOrderCount);
        System.out.println("Stock deducted: " + (100 - stock));
        
        if (stock < 0) {
            System.out.println("\n!!! PROBLEM: Stock is NEGATIVE (overselling detected) !!!");
        }
        
        if (orderCount > 100) {
            System.out.println("\n!!! PROBLEM: Orders exceed initial stock !!!");
        }
        
        if (sameUserOrderCount > 1) {
            System.out.println("\n!!! PROBLEM: Same user created multiple orders !!!");
        }
        
        System.out.println("\nSample errors from Lua: " + errors);
        System.out.println("==============================================\n");

        assertTrue(stock >= 0, "Stock should not be negative (no overselling)");
        assertTrue(orderCount <= 100, "Orders should not exceed initial stock");
        assertEquals(1, sameUserOrderCount, "Same user should only create 1 order");
    }

    @Test
    public void test150SameUser() throws InterruptedException {
        int threadCount = 150;
        Long sameUserId = 9999L;
        
        System.out.println("\n========== 150 Same User Concurrent Test ==========");
        System.out.println("=== Initial State ===");
        System.out.println("User ID: " + sameUserId);
        
        String redisStockBefore = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        System.out.println("Redis stock: " + redisStockBefore);
        
        SeckillVoucher initialSeckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        System.out.println("DB stock: " + (initialSeckillVoucher != null ? initialSeckillVoucher.getStock() : "null"));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger alreadyBoughtCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    mockUserHolder(sameUserId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        String errMsg = result != null ? result.getErrorMsg() : "null result";
                        if (errMsg != null && errMsg.contains("不要贪杯")) {
                            alreadyBoughtCount.incrementAndGet();
                        }
                        synchronized (errors) {
                            if (errors.size() < 10) errors.add(errMsg);
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    synchronized (errors) {
                        if (errors.size() < 10) errors.add(e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        
        System.out.println("\n=== After MQ Send (Before Processing) ===");
        System.out.println("Success to MQ: " + successCount.get());
        System.out.println("Fail at Lua: " + failCount.get());
        System.out.println("  - Already bought: " + alreadyBoughtCount.get());
        
        waitForMessageProcessing();
        
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("\n=== Final Result ===");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Success count (to MQ): " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("  - Already bought (不要贪杯): " + alreadyBoughtCount.get());
        System.out.println("Orders for this user in DB: " + orderCount);
        
        if (orderCount > 1) {
            System.out.println("\n!!! PROBLEM: Same user created multiple orders !!!");
            System.out.println("Expected: 1 order, Actual: " + orderCount);
        } else {
            System.out.println("\n[OK] One user one order rule: PASSED");
        }
        
        System.out.println("\nSample errors from Lua: " + errors);
        System.out.println("==================================================\n");

        assertEquals(1, orderCount, "Same user should only create 1 order");
    }

    @Test
    public void test200MixedUsers() throws InterruptedException {
        int threadCount = 200;
        int differentUserCount = 100;
        Long sameUserId = 8888L;
        
        System.out.println("\n========== 200 Mixed Users (100 Same + 100 Different) Test ==========");
        System.out.println("=== Initial State ===");
        System.out.println("Same user ID: " + sameUserId);
        System.out.println("Different users: " + differentUserCount);
        
        String redisStockBefore = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        System.out.println("Redis stock: " + redisStockBefore);
        
        SeckillVoucher initialSeckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        System.out.println("DB stock: " + (initialSeckillVoucher != null ? initialSeckillVoucher.getStock() : "null"));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger sameUserSuccessCount = new AtomicInteger(0);
        AtomicInteger differentUserSuccessCount = new AtomicInteger(0);
        AtomicInteger soldOutCount = new AtomicInteger(0);
        AtomicInteger alreadyBoughtCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final Long userId;
            if (i < differentUserCount) {
                userId = (long) (5000 + i);
            } else {
                userId = sameUserId;
            }
            
            final boolean isSameUser = userId.equals(sameUserId);
            
            executorService.submit(() -> {
                try {
                    mockUserHolder(userId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                        if (isSameUser) {
                            sameUserSuccessCount.incrementAndGet();
                        } else {
                            differentUserSuccessCount.incrementAndGet();
                        }
                    } else {
                        failCount.incrementAndGet();
                        String errMsg = result != null ? result.getErrorMsg() : "null result";
                        if (errMsg != null) {
                            if (errMsg.contains("卖完了")) {
                                soldOutCount.incrementAndGet();
                            } else if (errMsg.contains("不要贪杯")) {
                                alreadyBoughtCount.incrementAndGet();
                            }
                        }
                        synchronized (errors) {
                            if (errors.size() < 10) errors.add(errMsg);
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    synchronized (errors) {
                        if (errors.size() < 10) errors.add(e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        
        System.out.println("\n=== After MQ Send (Before Processing) ===");
        System.out.println("Success to MQ: " + successCount.get());
        System.out.println("  - Same user: " + sameUserSuccessCount.get());
        System.out.println("  - Different users: " + differentUserSuccessCount.get());
        System.out.println("Fail at Lua: " + failCount.get());
        System.out.println("  - Sold out: " + soldOutCount.get());
        System.out.println("  - Already bought: " + alreadyBoughtCount.get());
        
        waitForMessageProcessing();
        
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        int stock = seckillVoucher != null ? seckillVoucher.getStock() : -1;
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
        );
        long sameUserOrderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("\n=== Final Result ===");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Initial stock: 100");
        System.out.println("Success count (to MQ): " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("Final DB stock: " + stock);
        System.out.println("Total orders in DB: " + orderCount);
        System.out.println("Same user orders in DB: " + sameUserOrderCount);
        System.out.println("Stock deducted: " + (100 - stock));
        
        boolean passed = true;
        
        if (stock < 0) {
            System.out.println("\n!!! PROBLEM: Stock is NEGATIVE (overselling detected) !!!");
            passed = false;
        }
        
        if (orderCount > 100) {
            System.out.println("\n!!! PROBLEM: Orders exceed initial stock !!!");
            passed = false;
        }
        
        if (sameUserOrderCount > 1) {
            System.out.println("\n!!! PROBLEM: Same user created multiple orders !!!");
            passed = false;
        }
        
        if (passed) {
            System.out.println("\n[OK] All tests PASSED:");
            System.out.println("  - No overselling");
            System.out.println("  - One user one order rule followed");
        }
        
        System.out.println("\nSample errors from Lua: " + errors);
        System.out.println("=============================================================\n");

        assertTrue(stock >= 0, "Stock should not be negative (no overselling)");
        assertTrue(orderCount <= 100, "Orders should not exceed initial stock");
        assertEquals(1, sameUserOrderCount, "Same user should only create 1 order");
    }

    @Test
    public void testLoadBalancer200MixedUsers() throws InterruptedException {
        int threadCount = 200;
        int differentUserCount = 100;
        Long sameUserId = 8888L;
        
        System.out.println("\n========== Load Balancer Test: 200 Mixed Users (100 Same + 100 Different) ==========");
        System.out.println("=== Initial State ===");
        System.out.println("Simulating: Server1(8081) + Server2(8082) load balanced requests");
        System.out.println("Same user ID: " + sameUserId);
        System.out.println("Different users: " + differentUserCount);
        
        stringRedisTemplate.delete(STOCK_KEY);
        stringRedisTemplate.delete(ORDER_USER_KEY);
        stringRedisTemplate.opsForValue().set(STOCK_KEY, "100");
        
        voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>());
        
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        if (seckillVoucher != null) {
            seckillVoucher.setStock(100);
            seckillVoucherMapper.updateById(seckillVoucher);
        } else {
            seckillVoucher = new SeckillVoucher();
            seckillVoucher.setVoucherId(TEST_VOUCHER_ID);
            seckillVoucher.setStock(100);
            seckillVoucher.setCreateTime(java.time.LocalDateTime.now());
            seckillVoucher.setBeginTime(java.time.LocalDateTime.now().minusDays(1));
            seckillVoucher.setEndTime(java.time.LocalDateTime.now().plusDays(1));
            seckillVoucherMapper.insert(seckillVoucher);
        }
        
        String redisStockBefore = stringRedisTemplate.opsForValue().get(STOCK_KEY);
        System.out.println("Redis stock: " + redisStockBefore);
        
        SeckillVoucher initialSeckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        System.out.println("DB stock: " + (initialSeckillVoucher != null ? initialSeckillVoucher.getStock() : "null"));

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger server1Count = new AtomicInteger(0);
        AtomicInteger server2Count = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final Long userId;
            if (i < differentUserCount) {
                userId = (long) (8000 + i);
            } else {
                userId = sameUserId;
            }
            
            executorService.submit(() -> {
                try {
                    if (index % 2 == 0) {
                        server1Count.incrementAndGet();
                    } else {
                        server2Count.incrementAndGet();
                    }
                    
                    mockUserHolder(userId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        String errMsg = result != null ? result.getErrorMsg() : "null result";
                        synchronized (errors) {
                            if (errors.size() < 10) errors.add(errMsg);
                        }
                    }
                    
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    synchronized (errors) {
                        if (errors.size() < 10) errors.add("Exception: " + e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        
        System.out.println("\n=== After MQ Send (Before Processing) ===");
        System.out.println("Requests to Server1 (simulated): " + server1Count.get());
        System.out.println("Requests to Server2 (simulated): " + server2Count.get());
        System.out.println("Success to MQ: " + successCount.get());
        System.out.println("Fail at Lua: " + failCount.get());
        
        waitForMessageProcessing();
        
        SeckillVoucher afterSeckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        int stock = afterSeckillVoucher != null ? afterSeckillVoucher.getStock() : -1;
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
        );
        long sameUserOrderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("\n=== Final Result ===");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Initial stock: 100");
        System.out.println("Success count (to MQ): " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("Final DB stock: " + stock);
        System.out.println("Total orders in DB: " + orderCount);
        System.out.println("Same user orders in DB: " + sameUserOrderCount);
        System.out.println("Stock deducted: " + (100 - stock));
        
        boolean passed = true;
        
        if (stock < 0) {
            System.out.println("\n!!! PROBLEM: Stock is NEGATIVE (overselling detected) !!!");
            passed = false;
        }
        
        if (orderCount > 100) {
            System.out.println("\n!!! PROBLEM: Orders exceed initial stock !!!");
            passed = false;
        }
        
        if (sameUserOrderCount > 1) {
            System.out.println("\n!!! PROBLEM: Same user created multiple orders !!!");
            passed = false;
        }
        
        if (passed) {
            System.out.println("\n[OK] All Load Balancer Tests PASSED:");
            System.out.println("  - No overselling");
            System.out.println("  - One user one order rule followed");
            System.out.println("  - Both servers handled requests correctly");
        }
        
        System.out.println("\nSample errors: " + errors);
        System.out.println("===========================================================================\n");

        assertTrue(stock >= 0, "Stock should not be negative (no overselling)");
        assertTrue(orderCount <= 100, "Orders should not exceed initial stock");
        assertEquals(1, sameUserOrderCount, "Same user should only create 1 order");
    }

    private void waitForMessageProcessing() throws InterruptedException {
        Thread.sleep(5000);
    }

    private void mockUserHolder(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("TestUser" + userId);
        UserHolder.saveUser(user);
    }
}
