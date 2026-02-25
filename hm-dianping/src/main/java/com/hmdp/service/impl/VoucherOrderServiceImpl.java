package com.hmdp.service.impl;

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

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;


    private static final String CACHE_NAME_OF_SECKILL_VOUCHER = "cache:seckillVoucher::";
    private static final String CACHE_NAME_OF_VOUCHER = "cache:voucher::";

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //查询限时优惠卷信息
        SeckillVoucher seckillVoucher = redisUtil.getValueTTL(CACHE_NAME_OF_VOUCHER, voucherId, SeckillVoucher.class, seckillVoucherMapper);
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

        //减库存
        seckillVoucher.setStock(seckillVoucher.getStock()-1);
        int rows = seckillVoucherMapper.updateById(seckillVoucher);
        if(rows <= 0){
            throw new RuntimeException("库存扣除失败");
        }
        //TODO redis缓存在这里好像没有意义

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
