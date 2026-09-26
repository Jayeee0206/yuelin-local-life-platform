package com.yuelin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.dto.Result;
import com.yuelin.entity.Voucher;
import com.yuelin.mapper.VoucherMapper;
import com.yuelin.entity.SeckillVoucher;
import com.yuelin.service.ISeckillVoucherService;
import com.yuelin.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.yuelin.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = lambdaQuery()
                .eq(Voucher::getShopId, shopId)
                .eq(Voucher::getStatus, 1)
                .list();
        List<Long> seckillIds = vouchers.stream()
                .filter(voucher -> Integer.valueOf(1).equals(voucher.getType()))
                .map(Voucher::getId)
                .collect(Collectors.toList());
        Map<Long, SeckillVoucher> seckillByVoucher = seckillIds.isEmpty()
                ? Collections.emptyMap()
                : seckillVoucherService.listByIds(seckillIds).stream()
                        .collect(Collectors.toMap(SeckillVoucher::getVoucherId, Function.identity()));
        vouchers.forEach(voucher -> {
            SeckillVoucher seckill = seckillByVoucher.get(voucher.getId());
            if (seckill != null) {
                voucher.setStock(seckill.getStock());
                voucher.setBeginTime(seckill.getBeginTime());
                voucher.setEndTime(seckill.getEndTime());
            }
        });
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        if (voucher.getStock() == null || voucher.getStock() < 0
                || voucher.getBeginTime() == null || voucher.getEndTime() == null
                || !voucher.getEndTime().isAfter(voucher.getBeginTime())) {
            throw new IllegalArgumentException("Invalid seckill voucher stock or time range");
        }
        if (!save(voucher)) {
            throw new IllegalStateException("Failed to persist voucher");
        }
        SeckillVoucher seckillVoucher = new SeckillVoucher()
                .setVoucherId(voucher.getId())
                .setStock(voucher.getStock())
                .setBeginTime(voucher.getBeginTime())
                .setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("Failed to persist seckill voucher");
        }

        // 只在数据库事务提交成功后公开 Redis 库存，避免回滚后留下可售的孤儿库存。
        Runnable publishStock = () -> {
            try {
                stringRedisTemplate.opsForValue().set(
                        SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
            } catch (RuntimeException cacheFailure) {
                // 数据库已是权威状态；定时库存审计会继续发现并修复 Redis 缺失。
                log.error("Failed to publish seckill stock after commit: voucherId={}", voucher.getId(), cacheFailure);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishStock.run();
                }
            });
        } else {
            publishStock.run();
        }
    }
}
