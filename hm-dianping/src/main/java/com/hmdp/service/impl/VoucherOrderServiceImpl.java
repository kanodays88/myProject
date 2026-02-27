package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisUtil;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
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
    private SeckillVoucherMapper seckillVoucherMapper;


    private static final String CACHE_NAME_OF_SECKILL_VOUCHER_ORDER = "cache:seckillVoucher::";
    private static final String CACHE_NAME_OF_VOUCHER_ORDER = "cache:voucher::";
    // 定义全局的锁容器，存储每个用户ID对应的锁对象
    private static final ConcurrentHashMap<Long,Object> USER_LOCKS = new ConcurrentHashMap<>();

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询限时优惠卷信息
        SeckillVoucher seckillVoucher = seckillVoucherMapper.selectById(voucherId);
        if(seckillVoucher == null){
            return Result.fail("该优惠卷不是限时秒杀优惠卷");
        }
        //判断该限时优惠卷是否处于秒杀时间
        if(LocalDateTime.now().compareTo(seckillVoucher.getBeginTime()) < 0){
            return Result.fail("秒杀未开始");
        }
        if(LocalDateTime.now().compareTo(seckillVoucher.getEndTime()) > 0){
            return Result.fail("秒杀已结束");
        }
        //判断库存是否充足
        if(seckillVoucher.getStock() <= 0){
            return Result.fail("卖完了");
        }
        /**
         * 这里t->new Object()相当于   private Object function(Object k){return new Object()}
         * computeIfAbsent()方法：当键值对不存在时创建键值对，存在则直接返回键对应的值
         * 这里的核心思路就是为每个userId创建一个Object实体，多个线程的相同userId在这个HashMap上只能获取到相同的Object实体,这里的Obejct相当于标识userId
         */
        Object userLock = USER_LOCKS.computeIfAbsent(UserHolder.getUser().getId(), t -> new Object());
        //添加悲观锁,多个相同的userLock,有且只有一个能够进入synchronized
        synchronized (userLock) {
            Result r = null;
            try{
                r = voucherOrderServiceImpl.getResult(seckillVoucher);
            }finally {
                //清除锁的实体避免内存泄露
                USER_LOCKS.remove(UserHolder.getUser().getId());
            }
            return r;
        }
    }

    @Override
    @Transactional
    public Result getResult(SeckillVoucher seckillVoucher) {
        //判断该用户是否已经购买过了
        LambdaQueryWrapper<VoucherOrder> voucherOrderWrapper = new LambdaQueryWrapper<>();
        voucherOrderWrapper.eq(VoucherOrder::getVoucherId,seckillVoucher.getVoucherId()).eq(VoucherOrder::getUserId,UserHolder.getUser().getId());
        List<VoucherOrder> voucherOrders = voucherOrderMapper.selectList(voucherOrderWrapper);
        if(voucherOrders != null && voucherOrders.size() >= 1){
            //该用户已经购买过
            return Result.fail("不能贪杯哦");
        }

        //减库存
        //用乐观锁解决超卖问题，用库存量是否大于0来判断
        //lambda条件构造器
        LambdaUpdateWrapper<SeckillVoucher> seckillVoucherWrapper = new LambdaUpdateWrapper<>();
        //不能先查询库存A再修改A,然后通过update方式修改库存，因为如果有其他线程先修改了库存，这时你再通过update的方式写入A,就会数据错乱，
        seckillVoucherWrapper.setSql("stock = stock-1").gt(SeckillVoucher::getStock,0).eq(SeckillVoucher::getVoucherId, seckillVoucher.getVoucherId());//条件构造，现库存大于0才可执行（及库存不变）
        int rows = seckillVoucherMapper.update(null,seckillVoucherWrapper);
        if(rows <= 0){
            //回滚表示撤销当前事务的操作，如果当前事务并没有对数据进行修改，那么触发的回滚也不会修改任何数据
            throw new RuntimeException("库存扣除失败");
        }


        //创建订单id
        long order = redisUtil.nextId("order");
        //用户id
        Long id = UserHolder.getUser().getId();

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(seckillVoucher.getVoucherId());
        voucherOrder.setId(order);
        voucherOrder.setUserId(id);
        rows = voucherOrderMapper.insert(voucherOrder);
        if(rows <= 0){
            throw new RuntimeException("优惠卷订单新增失败");
        }
        return Result.ok(order);
    }
}
