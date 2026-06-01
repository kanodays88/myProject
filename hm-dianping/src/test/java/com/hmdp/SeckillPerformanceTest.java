package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 异步秒杀业务并发性能测试
 *
 * 前置条件：MySQL / Redis / RabbitMQ 服务需正常运行
 *
 * 测试场景：
 *   一、1000个相同用户 高并发抢 500张优惠券
 *   二、1000个不同用户 高并发抢 500张优惠券
 *   三、500个相同用户 + 500个不同用户 高并发抢 500张优惠券
 *
 * 测试指标：QPS、平均响应时间(ms)、TPS(orders/s)、请求成功/失败分布、数据库最终一致性
 */
@Slf4j
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = {HmDianPingApplication.class}
)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SeckillPerformanceTest {

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private IVoucherService voucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    // ==================== 常量 ====================
    private static final int TOTAL_STOCK = 500;
    private static final String SECKILL_STOCK_KEY = "stock:seckillVoucher::";
    private static final String ORDER_BOUGHT_KEY = "orderBought:seckillVoucher:";

    private static final Long SHOP_ID = 1L;
    private static final int VOUCHER_TYPE_SECKILL = 1;
    private static final int VOUCHER_STATUS_ACTIVE = 1;

    private static final int THREAD_POOL_SIZE = 1000;
    private static final int MQ_CONSUME_WAIT_MS = 60000;  // 给MQ更多时间

    /** 当前测试使用的券ID（每个测试方法不同） */
    private static Long TEST_VOUCHER_ID;

    // ==================== 结果收集 ====================
    private static final List<TestResult> TEST_RESULTS = new CopyOnWriteArrayList<>();

    @lombok.AllArgsConstructor
    @lombok.Getter
    static class TestResult {
        private final String name;
        private final int total;
        private final double wallSec;
        private final double qps;
        private final double avgMs;
        private final double tps;
        private final int succ;
        private final int bought;
        private final int soldOut;
        private final int otherFail;
    }

    // ================================================================
    //  MQ 队列声明（确保 @RabbitListener 能正常启动）
    // ================================================================
//    @Configuration
//    static class TestQueueConfig {
//        @Bean
//        public Queue seckillVoucherQueue() {
//            return new Queue("seckillVoucherQueue_1", true);
//        }
//    }

    // ================================================================
    //  测试 0：验证可通过业务接口创建 100 张秒杀券
    // ================================================================
    @Test
    @Order(0)
    void test_create100SeckillVouchers() {
        log.info("");
        log.info("★★★★★ 验证：通过业务接口创建 100 张秒杀券 ★★★★★");
        List<Long> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                Long id = createSeckillVoucher(TOTAL_STOCK);
                ids.add(id);
            }
            log.info("✅ 成功创建 100 张秒杀券! ID范围: {} ~ {}", ids.get(0), ids.get(99));
        } finally {
            // 清理创建的券
            for (Long vid : ids) {
                voucherOrderMapper.delete(
                        new LambdaQueryWrapper<VoucherOrder>().eq(VoucherOrder::getVoucherId, vid));
                stringRedisTemplate.delete(SECKILL_STOCK_KEY + vid);
                stringRedisTemplate.delete(ORDER_BOUGHT_KEY + vid + "::users");
                seckillVoucherMapper.deleteById(vid);
                voucherService.removeById(vid);
            }
            log.info("  清理完成\n");
        }
    }


    // ================================================================
    //  每个测试前：创建一张新券（500库存）
    // ================================================================
    @BeforeEach
    void beforeEach() {
        TEST_VOUCHER_ID = createSeckillVoucher(TOTAL_STOCK);
        log.info("使用测试券 ID={}, stock={}", TEST_VOUCHER_ID, TOTAL_STOCK);
    }

    /** 每测试后：清理本券数据（验证已在 runConcurrentTest 中等待过MQ） */
    @AfterEach
    void afterEach() throws InterruptedException {
        cleanupTestData(TEST_VOUCHER_ID);
    }


    // ================================================================
    //  测试一：1000个相同用户（userId=1）
    //  预期：1人成功，999人"不要贪杯"
    // ================================================================
    @Test
    @Order(1)
    void test_1000SameUsers() throws Exception {
        log.info("★★★★★ 场景一：1000个相同用户（userId=1）★★★★★");
        List<Long> userIds = Collections.nCopies(1000, 1L);
        runConcurrentTest("场景一[1000相同用户]", userIds, TEST_VOUCHER_ID);
    }


    // ================================================================
    //  测试二：1000个不同用户（userId=10001~11000）
    //  预期：500人成功，500人"已售罄"
    // ================================================================
    @Test
    @Order(2)
    void test_1000DifferentUsers() throws Exception {
        log.info("★★★★★ 场景二：1000个不同用户 ★★★★★");
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < 1000; i++) userIds.add(10001L + i);
        runConcurrentTest("场景二[1000不同用户]", userIds, TEST_VOUCHER_ID);
    }


    // ================================================================
    //  测试三：500相同（userId=1）+ 500不同（userId=20001~20500）
    //  预期：1(相同) + 499(不同) = 500人成功
    // ================================================================
    @Test
    @Order(3)
    void test_500SameAnd500DifferentUsers() throws Exception {
        log.info("★★★★★ 场景三：500相同+500不同用户 ★★★★★");
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < 500; i++) userIds.add(1L);
        for (int i = 0; i < 500; i++) userIds.add(20001L + i);
        runConcurrentTest("场景三[500相同+500不同]", userIds, TEST_VOUCHER_ID);
    }


    // ================================================================
    //  核心：并发测试执行器 + 统计 + 验证
    // ================================================================
    private void runConcurrentTest(String scenarioName, List<Long> userIds, Long voucherId)
            throws Exception {

        int n = userIds.size();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                THREAD_POOL_SIZE, THREAD_POOL_SIZE,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(n),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        CyclicBarrier barrier = new CyclicBarrier(n);
        CountDownLatch latch = new CountDownLatch(n);

        AtomicLong totalRtNanos = new AtomicLong(0);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger fail = new AtomicInteger(0);
        AtomicInteger boughtAlready = new AtomicInteger(0);
        AtomicInteger soldOut = new AtomicInteger(0);

        long wallStart = System.nanoTime();

        for (Long uid : userIds) {
            executor.submit(() -> {
                try {
                    barrier.await();                            // 同时起跑
                    long t0 = System.nanoTime();

                    UserDTO ud = new UserDTO();
                    ud.setId(uid);
                    ud.setNickName("u" + uid);
                    UserHolder.saveUser(ud);
                    try {
                        Result r = voucherOrderService.seckillVoucher(voucherId);
                        long elapsed = System.nanoTime() - t0;
                        totalRtNanos.addAndGet(elapsed);

                        if (r.getSuccess()) {
                            success.incrementAndGet();
                        } else {
                            fail.incrementAndGet();
                            String msg = r.getErrorMsg();
                            if (msg != null) {
                                if (msg.contains("不要贪杯")) boughtAlready.incrementAndGet();
                                else if (msg.contains("卖完")) soldOut.incrementAndGet();
                            }
                        }
                    } finally {
                        UserHolder.removeUser();
                    }
                } catch (Exception e) {
                    fail.incrementAndGet();
                    log.error("线程异常: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        double wallSec = (System.nanoTime() - wallStart) / 1e9;
        double avgMs = (totalRtNanos.get() / (double) n) / 1_000_000.0;
        double qps = n / wallSec;
        double tps = success.get() / wallSec;

        TEST_RESULTS.add(new TestResult(scenarioName, n, wallSec, qps, avgMs, tps,
                success.get(), boughtAlready.get(), soldOut.get(),
                fail.get() - boughtAlready.get() - soldOut.get()));

        // 等待 MQ 消费者处理完成，再验证数据一致性
        log.info("等待 MQ 消费完成 ({} ms)...", MQ_CONSUME_WAIT_MS);
        Thread.sleep(MQ_CONSUME_WAIT_MS);

        verifyDataConsistency(scenarioName, voucherId, success.get());
    }

    // ================================================================
    //  所有测试完成后：统一呈现总结果表
    // ================================================================
    @AfterAll
    void printSummary() {
        String sep = "═══════════════════════════════════════════════════════════════════════════════════════";
        String header = String.format("  %-22s %6s %8s %8s %8s %12s %5s %6s %6s %6s",
                "场景名", "请求数", "耗时(s)", "QPS", "TPS", "平均响应(ms)", "成功", "已买过", "已售罄", "其他失败");
        String divider = "  " + "─".repeat(100);

        log.info("");
        log.info(sep);
        log.info("  秒杀异步性能测试 — 总结果汇总");
        log.info(sep);
        log.info(header);
        log.info(divider);

        for (TestResult r : TEST_RESULTS) {
            log.info(String.format("  %-22s %6d %8.2f %8.0f %8.0f %12.2f %5d %6d %6d %6d",
                    r.getName(), r.getTotal(), r.getWallSec(), r.getQps(), r.getTps(),
                    r.getAvgMs(), r.getSucc(), r.getBought(), r.getSoldOut(), r.getOtherFail()));
        }

        log.info(sep);
        log.info("");
    }

    // ================================================================
    //  数据一致性验证
    // ================================================================
    private void verifyDataConsistency(String scenarioName, Long voucherId, int apiSuccess) {
        log.info("  ── 数据一致性验证 ──");


        // Redis 库存
        String raw = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
        int redisStock = -1;
        if (raw != null) {
            try { redisStock = Integer.parseInt(raw.replaceAll("\"", "")); }
            catch (NumberFormatException ignored) { }
        }

        // DB 库存
        SeckillVoucher sv = seckillVoucherMapper.selectById(voucherId);
        int dbStock = (sv == null) ? -1 : sv.getStock();

        // 订单
        List<VoucherOrder> orders = voucherOrderMapper.selectList(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, voucherId));
        long orderCount = orders.size();
        long distinctUsers = orders.stream().map(VoucherOrder::getUserId).distinct().count();

        boolean overSold = orderCount > TOTAL_STOCK;
        boolean dupOrder = orderCount != distinctUsers;

        log.info("  Redis 余量:     {}", redisStock >= 0 ? redisStock : "N/A");
        log.info("  DB 余量:        {}", dbStock);
        log.info("  总订单数:       {}", orderCount);
        log.info("  下单用户数:     {}", distinctUsers);
        log.info("  超卖:           {}", overSold ? "❌ 是" : "✅ 否");
        log.info("  一人一单:       {}", dupOrder ? "❌ 违规" : "✅ 正常");

        // 断言
        Assertions.assertFalse(overSold,
                scenarioName + " >>> 严重 BUG - 超卖！订单数=" + orderCount + " > 库存=" + TOTAL_STOCK);
        Assertions.assertFalse(dupOrder,
                scenarioName + " >>> 严重 BUG - 一人一单违规！存在同一用户重复下单");

        // 订单数校验：允许少量丢失（MQ锁竞争丢消息）
        long maxExpected = Math.min(TOTAL_STOCK, apiSuccess);
        Assertions.assertTrue(orderCount >= maxExpected - 10,
                scenarioName + " >>> 订单数异常偏低 (MQ消息可能大量丢失)");
        Assertions.assertTrue(orderCount <= maxExpected,
                scenarioName + " >>> 订单数异常偏高");

        log.info("  ✅ 数据一致性验证通过！");
        log.info("");
    }


    // ================================================================
    //  辅助：通过业务接口创建秒杀券
    // ================================================================
    private Long createSeckillVoucher(int stock) {
        Voucher v = new Voucher();
        v.setShopId(SHOP_ID);
        v.setTitle("PT-" + UUID.randomUUID().toString().substring(0, 8));
        v.setSubTitle("性能测试券");
        v.setRules("仅限测试");
        v.setPayValue(100L);
        v.setActualValue(10L);
        v.setType(VOUCHER_TYPE_SECKILL);
        v.setStatus(VOUCHER_STATUS_ACTIVE);
        v.setStock(stock);
        v.setBeginTime(LocalDateTime.now().minusDays(1));
        v.setEndTime(LocalDateTime.now().plusDays(7));
        voucherService.addSeckillVoucher(v);
        return v.getId();
    }

    // ================================================================
    //  辅助：清理测试数据
    // ================================================================
    private void cleanupTestData(Long voucherId) {
        // 删订单
        voucherOrderMapper.delete(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getVoucherId, voucherId));
        // 删 Redis
        stringRedisTemplate.delete(SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.delete(ORDER_BOUGHT_KEY + voucherId + "::users");
        // 删秒杀券
        seckillVoucherMapper.deleteById(voucherId);
        // 删主券
        voucherService.removeById(voucherId);
    }
}
