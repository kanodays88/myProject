package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("server1")
public class SeckillVoucherClusterTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    private static final Long TEST_VOUCHER_ID = 11L;
    private static final String SERVER1_URL = "http://localhost:8081/api/voucher-order/seckill/";
    private static final String SERVER2_URL = "http://localhost:8082/api/voucher-order/seckill/";

    @BeforeEach
    public void setUp() {
        voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>());
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        if (seckillVoucher != null) {
            seckillVoucher.setStock(100);
            seckillVoucherMapper.updateById(seckillVoucher);
        }
    }

    @Test
    public void testClusterDifferentUsersNoOverselling() throws InterruptedException {
        int threadCount = 200;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 1; i <= threadCount; i++) {
            final Long userId = (long) (2000 + i);
            final String url = (i % 2 == 0) ? SERVER1_URL : SERVER2_URL;
            
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    try {
                        Result result = callSeckillApi(url, userId);
                        if (result != null && result.getSuccess()) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executorService.shutdown();

        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        int stock = seckillVoucher != null ? seckillVoucher.getStock() : -1;
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
        );

        System.out.println("========== Cluster Test: 200 Different Users ==========");
        System.out.println("Server 1: port 8081, Server 2: port 8082");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Success count: " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("Initial stock: 100");
        System.out.println("Final stock: " + stock);
        System.out.println("Total orders: " + orderCount);
        System.out.println("Stock deducted: " + (100 - stock));
        System.out.println("======================================================");

        assertTrue(stock >= 0, "Stock should not be negative");
        assertEquals(100, orderCount, "Should have exactly 100 orders (no overselling)");
    }

    @Test
    public void testClusterSameUserFromDifferentServers() throws InterruptedException {
        Long sameUserId = 9998L;
        
        voucherOrderMapper.delete(new LambdaQueryWrapper<VoucherOrder>());
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        if (seckillVoucher != null) {
            seckillVoucher.setStock(100);
            seckillVoucherMapper.updateById(seckillVoucher);
        }

        int threadCount = 50;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String url = (i % 2 == 0) ? SERVER1_URL : SERVER2_URL;
            
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    try {
                        Result result = callSeckillApi(url, sameUserId);
                        if (result != null && result.getSuccess()) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executorService.shutdown();

        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("========== Cluster Test: Same User From 2 Servers ==========");
        System.out.println("User ID: " + sameUserId);
        System.out.println("Requests sent to both server1(8081) and server2(8082)");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Success count: " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("Orders for this user: " + orderCount);
        System.out.println("=========================================================");

        assertEquals(1, orderCount, "Same user should only create 1 order even from cluster");
    }

    private Result callSeckillApi(String baseUrl, Long userId) {
        try {
            String url = baseUrl + TEST_VOUCHER_ID;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            return restTemplate.postForObject(url, new HttpEntity<>(headers), Result.class);
        } catch (Exception e) {
            System.out.println("Request failed: " + e.getMessage());
            return Result.fail("Request failed");
        }
    }
}
