package com.yuelin.service;

import com.yuelin.dto.Result;
import com.yuelin.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    boolean createVoucherOrder(VoucherOrder voucherOrder);

}
