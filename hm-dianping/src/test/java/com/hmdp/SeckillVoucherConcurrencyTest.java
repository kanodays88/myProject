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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class SeckillVoucherConcurrencyTest {
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
    public void testDifferentUsersNoOverselling() throws InterruptedException {
        int threadCount = 200;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();

        for (int i = 1; i <= threadCount; i++) {
            final Long userId = (long) (1000 + i);
            executorService.submit(() -> {
                try {
                    mockUserHolder(userId);
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

        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(TEST_VOUCHER_ID);
        int stock = seckillVoucher != null ? seckillVoucher.getStock() : -1;
        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
        );

        System.out.println("========== Test 1: 200 Different Users ==========");
        System.out.println("Total threads: " + threadCount);
        System.out.println("Success count: " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("Initial stock: 100");
        System.out.println("Final stock: " + stock);
        System.out.println("Total orders: " + orderCount);
        System.out.println("Stock deducted: " + (100 - stock));
        System.out.println("Sample errors: " + errors);
        System.out.println("================================================");

        if (orderCount > 100) {
            System.out.println("WARNING: Overselling detected! Orders exceed stock!");
        }
        assertTrue(stock >= 0, "Stock should not be negative (no overselling)");
    }

    @Test
    public void testSameUserNoDuplicateOrder() throws InterruptedException {
        int threadCount = 100;
        Long sameUserId = 9999L;

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

        long orderCount = voucherOrderMapper.selectCount(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, TEST_VOUCHER_ID)
                        .eq(VoucherOrder::getUserId, sameUserId)
        );

        System.out.println("========== Test 2: 100 Same User ==========");
        System.out.println("User ID: " + sameUserId);
        System.out.println("Total threads: " + threadCount);
        System.out.println("Success count: " + successCount.get());
        System.out.println("Fail count: " + failCount.get());
        System.out.println("Orders for this user: " + orderCount);
        System.out.println("Sample errors: " + errors);
        System.out.println("============================================");

        assertEquals(1, orderCount, "Same user should only create 1 order");
    }

    private void mockUserHolder(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("TestUser" + userId);
        UserHolder.saveUser(user);
    }
}
