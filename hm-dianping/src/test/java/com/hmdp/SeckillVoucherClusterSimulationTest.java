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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SeckillVoucherClusterSimulationTest {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    private static final Long TEST_VOUCHER_ID = 11L;

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
    public void testSimulateCluster_SameUserFromDifferentThreads() throws InterruptedException {
        int threadCount = 100;
        Long sameUserId = 8888L;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    mockUserHolder(sameUserId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("========== Cluster Simulation: Same User 100 Concurrent ==========");
        System.out.println("Simulates: 1 user sending 100 requests to cluster (any server)");
        System.out.println("User ID: " + sameUserId);
        System.out.println("Total requests: " + threadCount);
        System.out.println("Success: " + successCount.get());
        System.out.println("Rejected (duplicate): " + failCount.get());
        System.out.println("Final orders: " + orderCount);
        System.out.println("==============================================================");

        assertEquals(1, orderCount, "Same user should only create 1 order");
    }

    @Test
    public void testSimulateCluster_DifferentUsers() throws InterruptedException {
        int threadCount = 200;
        
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 1; i <= threadCount; i++) {
            final Long userId = (long) (3000 + i);
            executorService.submit(() -> {
                try {
                    mockUserHolder(userId);
                    var result = voucherOrderService.seckillVoucher(TEST_VOUCHER_ID);
                    if (result != null && result.getSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
        );

        System.out.println("========== Cluster Simulation: 200 Different Users ==========");
        System.out.println("Simulates: 200 different users sending requests to cluster");
        System.out.println("Total requests: " + threadCount);
        System.out.println("Success: " + successCount.get());
        System.out.println("Failed (no stock): " + failCount.get());
        System.out.println("Initial stock: 100");
        System.out.println("Final stock: " + (seckillVoucher != null ? seckillVoucher.getStock() : -1));
        System.out.println("Total orders: " + orderCount);
        System.out.println("============================================================");

        assertEquals(100, orderCount, "Should have exactly 100 orders (stock=100)");
        assertTrue(seckillVoucher == null || seckillVoucher.getStock() >= 0, "Stock should not be negative");
    }

    private void mockUserHolder(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("User" + userId);
        UserHolder.saveUser(user);
    }
}
