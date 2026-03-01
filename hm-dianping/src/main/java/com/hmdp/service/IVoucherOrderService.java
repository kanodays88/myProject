package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 限时优惠卷秒杀
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 内部方法，主要用于实现回滚
     * @param seckillVoucher
     * @return
     */
    Result getResult(SeckillVoucher seckillVoucher);
}
