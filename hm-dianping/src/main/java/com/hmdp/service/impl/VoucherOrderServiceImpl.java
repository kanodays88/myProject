package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisUtil;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private VoucherMapper voucherMapper;

    //默认单例模式不会循环注入
    @Autowired
    private IVoucherOrderService voucherOrderServiceImpl;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    //redis分布式锁名，拼接用户id
    private static final String REDIS_LOCK_NAME = "lock:seckillVoucherOrder::";


    private static final String CACHE_NAME_OF_SECKILL_VOUCHER_ORDER = "cache:seckillVoucher::";
    private static final String CACHE_NAME_OF_VOUCHER_ORDER = "cache:voucher::";
    // 定义全局的锁容器，存储每个用户ID对应的锁对象
    private static final ConcurrentHashMap<Long,Object> USER_LOCKS = new ConcurrentHashMap<>();

    //该UUID因为被设定为静态常量，所以只会在创建的时候初始化一次，后续永远不变，这里相当于用作标识该类
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-";

    private static final String SECKILL_VOUCHER_STOCK_KEY = "stock:seckillVoucher::";

    private static final String ORDER_BOUGHT_USER_KEY = "orderBought:seckillVoucher:";

    //lua脚本的加载类                        返回类型
    private static final DefaultRedisScript<Long> REDIS_SCRIPT;
    static {
        //初始化
        REDIS_SCRIPT = new DefaultRedisScript<>();
        //指定lua脚本                       将lua脚本包装成Resource类型
        REDIS_SCRIPT.setLocation(new ClassPathResource("Lua/seckillVoucher.lua"));
        //指定返回类型
        REDIS_SCRIPT.setResultType(Long.class);
    }


    //异步秒杀业务
    @Override
    public Result seckillVoucher(Long voucherId) {
        log.info("线程:{}执行seckillVoucher方法",Thread.currentThread().getId());
        //获取库存key,已购订单key调用lua脚本
        List<String> keys = new ArrayList<>();
        keys.add(SECKILL_VOUCHER_STOCK_KEY+voucherId);
        keys.add(ORDER_BOUGHT_USER_KEY+voucherId+"::users");
        log.info("key1:{},key2:{}",keys.get(0),keys.get(1));
        Long status = stringRedisTemplate.execute(REDIS_SCRIPT, keys, UserHolder.getUser().getId().toString());

        //没库存了
        if(status == 1){
            return Result.fail("卖完了");
        }
        //已经买过了
        if(status == 2){
            return Result.fail("不要贪杯哦");
        }

        //生成订单id
        long orderId = redisUtil.nextId("order");
        //将用户id,商品id,订单id存放到消息队列
//        String msg = UserHolder.getUser().getId().toString()+","+voucherId+","+orderId;
        Map<String,Object> msg = new HashMap<>();
        msg.put("orderId",orderId);
        msg.put("voucherId",voucherId);
        msg.put("userId",UserHolder.getUser().getId());

        String queueName = "seckillVoucherQueue_1";
        //传输复杂类型，json序列化
        rabbitTemplate.convertAndSend(queueName,msg);

        return Result.ok(orderId);
    }

    @RabbitListener(queues = "seckillVoucherQueue_1")
    public void seckillVoucherListener(Map<String,Object> msg){
        log.info("线程:{}执行seckillVoucherListener方法",Thread.currentThread().getId());
//        String[] strings = msg.split(",");
//        Long userId = Long.valueOf(strings[0]);
//        Long voucherId = Long.valueOf(strings[1]);
//        Long orderId = Long.valueOf(strings[2]);
        //rabbitmq的json序列化会根据数字大小决定用Long还是Integer,我这里手动指定转换为long
        Long userId = ((Number) msg.get("userId")).longValue();
        Long voucherId = ((Number) msg.get("voucherId")).longValue();
        Long orderId = ((Number) msg.get("orderId")).longValue();


        //这里防止redis判断失误或者redis异常，做一个兜底作用，实际开发时有可能有多个消费者监听该队列
        //解决同一个账号高并发下单问题，实现一人一单
        //redis分布式锁版本，解决集群中锁不共用问题
        //尝试获取redis分布式锁                  锁名key: 作用：业务：对应用户                        值value: 当前线程的唯一标识id      上锁时间，单位秒
        boolean lockStatus = redisUtil.tryLock(REDIS_LOCK_NAME + voucherId, ID_PREFIX+Thread.currentThread().getId(), 120);
        if(lockStatus == false){
            //说明锁被占用，说明同一个用户在多次下单且该用户此时正在下单
            log.error("请勿重复操作");
            return;
        }
        //上锁成功，执行扣库存生成订单业务
//        Result r = null;//存储try块内的返回结果
        try{
            voucherOrderServiceImpl.getResult(orderId, userId,voucherId);
        }finally {
            //                      当前锁的key                                         该线程创建锁时的唯一标识
            redisUtil.deleteLock(REDIS_LOCK_NAME+ voucherId,ID_PREFIX+Thread.currentThread().getId());
//            //释放锁
//            //先判断该锁是不是由该线程自己创建的
//            String s = stringRedisTemplate.opsForValue().get(REDIS_LOCK_NAME + UserHolder.getUser().getId());
//            //这步释放锁和检验锁不是原子性，有可能检验成功但是删除失败导致锁一直存在，或者检验成功但是删除阻塞延迟了后续恢复删除了其他线程的锁
//            if((ID_PREFIX+Thread.currentThread().getId()).equals(s)){
//               //标识相同证明是同一个线程创建的锁，可以释放
//               redisUtil.deleteLock(REDISLOCKNAME + UserHolder.getUser().getId());
//            }
        }


    }

    @Override
    @Transactional
    public void getResult(Long orderId,Long userId,Long voucherId) {
        //防止redis出错有漏网之鱼
        //判断该用户是否已经购买过了
        LambdaQueryWrapper<VoucherOrder> voucherOrderWrapper = new LambdaQueryWrapper<>();
        voucherOrderWrapper.eq(VoucherOrder::getVoucherId,voucherId).eq(VoucherOrder::getUserId,userId);
        List<VoucherOrder> voucherOrders = voucherOrderMapper.selectList(voucherOrderWrapper);
        if(voucherOrders != null && voucherOrders.size() >= 1){
            //该用户已经购买过
            log.error("该用户已购买过");
            return;
        }
        //减库存
        //用乐观锁解决超卖问题，用库存量是否大于0来判断
        //lambda条件构造器
        LambdaUpdateWrapper<SeckillVoucher> seckillVoucherWrapper = new LambdaUpdateWrapper<>();
        //不能先查询库存A再修改A,然后通过update方式修改库存，因为如果有其他线程先修改了库存，这时你再通过update的方式写入A,就会数据错乱，
        seckillVoucherWrapper.setSql("stock = stock-1").gt(SeckillVoucher::getStock,0).eq(SeckillVoucher::getVoucherId,voucherId);//条件构造，现库存大于0才可执行（及库存不变）
        int rows = seckillVoucherMapper.update(null,seckillVoucherWrapper);
        if(rows <= 0){
            //回滚表示撤销当前事务的操作，如果当前事务并没有对数据进行修改，那么触发的回滚也不会修改任何数据
            throw new RuntimeException("库存扣除失败");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        rows = voucherOrderMapper.insert(voucherOrder);
        if(rows <= 0){
            throw new RuntimeException("优惠卷订单新增失败");
        }
    }









    //一条龙服务的秒杀业务
//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //查询限时优惠卷信息
//        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(voucherId);
//        if(seckillVoucher == null){
//            return Result.fail("该优惠卷不是限时秒杀优惠卷");
//        }
//        //判断该限时优惠卷是否处于秒杀时间
//        if(LocalDateTime.now().compareTo(seckillVoucher.getBeginTime()) < 0){
//            return Result.fail("秒杀未开始");
//        }
//        if(LocalDateTime.now().compareTo(seckillVoucher.getEndTime()) > 0){
//            return Result.fail("秒杀已结束");
//        }
//        //判断库存是否充足
//        if(seckillVoucher.getStock() <= 0){
//            return Result.fail("卖完了");
//        }
//        //解决同一个账号高并发下单问题，实现一人一单
//        //悲观锁版本
////        /**
////         * 这里t->new Object()相当于   private Object function(Object k){return new Object()}
////         * computeIfAbsent()方法：当键值对不存在时创建键值对，存在则直接返回键对应的值
////         * 这里的核心思路就是为每个userId创建一个Object实体，多个线程的相同userId在这个HashMap上只能获取到相同的Object实体,这里的Obejct相当于标识userId
////         */
////        Object userLock = USER_LOCKS.computeIfAbsent(UserHolder.getUser().getId(), t -> new Object());
////        //添加悲观锁,多个相同的userLock,有且只有一个能够进入synchronized
////        synchronized (userLock) {
////            Result r = null;
////            try{
////                r = voucherOrderServiceImpl.getResult(seckillVoucher);
////            }finally {
////                //清除锁的实体避免内存泄露
////                USER_LOCKS.remove(UserHolder.getUser().getId());
////            }
////            return r;
////        }
//
//
//        //解决同一个账号高并发下单问题，实现一人一单
//        //redis分布式锁版本，解决集群中锁不共用问题
//        //尝试获取redis分布式锁                  锁名key: 作用：业务：对应用户                        值value: 当前线程的唯一标识id      上锁时间，单位秒
//        boolean lockStatus = redisUtil.tryLock(REDIS_LOCK_NAME + UserHolder.getUser().getId(), ID_PREFIX+Thread.currentThread().getId(), 120);
//        if(lockStatus == false){
//            //说明锁被占用，说明同一个用户在多次下单且该用户此时正在下单
//            return Result.fail("请勿重复操作");
//        }
//        //上锁成功，执行扣库存生成订单业务
//        Result r = null;//存储try块内的返回结果
//        try{
//            r = voucherOrderServiceImpl.getResult(seckillVoucher);
//        }finally {
//            //                      当前锁的key                                         该线程创建锁时的唯一标识
//            redisUtil.deleteLock(REDIS_LOCK_NAME+UserHolder.getUser().getId(),ID_PREFIX+Thread.currentThread().getId());
////            //释放锁
////            //先判断该锁是不是由该线程自己创建的
////            String s = stringRedisTemplate.opsForValue().get(REDIS_LOCK_NAME + UserHolder.getUser().getId());
////            //这步释放锁和检验锁不是原子性，有可能检验成功但是删除失败导致锁一直存在，或者检验成功但是删除阻塞延迟了后续恢复删除了其他线程的锁
////            if((ID_PREFIX+Thread.currentThread().getId()).equals(s)){
////               //标识相同证明是同一个线程创建的锁，可以释放
////               redisUtil.deleteLock(REDISLOCKNAME + UserHolder.getUser().getId());
////            }
//        }
//
////        //获取锁对象
////        RLock lock = redissonClient.getLock(REDIS_LOCK_NAME + UserHolder.getUser().getId());
////        Result r = null;//存储结果
////        //尝试获取锁
////        boolean lockStatus = lock.tryLock(1, 5, TimeUnit.SECONDS);
////        if(lockStatus == false){
////            return Result.fail("请勿重复操作");
////        }
////        try{
////            r = voucherOrderServiceImpl.getResult(seckillVoucher);
////        }finally {
////            //释放锁
////            lock.unlock();
////        }
//
//
//        return r;
//    }
//
}
