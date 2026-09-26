package com.yuelin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.util.concurrent.RateLimiter;
import com.yuelin.dto.Result;
import com.yuelin.entity.SeckillVoucher;
import com.yuelin.entity.VoucherOrder;
import com.yuelin.mapper.VoucherOrderMapper;
import com.yuelin.messaging.MQSender;
import com.yuelin.service.ISeckillVoucherService;
import com.yuelin.service.IVoucherOrderService;
import com.yuelin.service.OrderResolutionService;
import java.util.Objects;
import com.yuelin.utils.RedisIdWorker;
import com.yuelin.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private MQSender mqSender;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private OrderResolutionService orderResolutionService;

    private RateLimiter rateLimiter;

    @Value("${yuelin.seckill.request-rate-per-second:1000}")
    private double requestRatePerSecond;

    @Value("${yuelin.messaging.enabled:true}")
    private boolean messagingEnabled;

    @PostConstruct
    public void initializeRateLimiter() {
        rateLimiter = RateLimiter.create(requestRatePerSecond);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        if (!messagingEnabled) {
            return Result.fail("秒杀消息服务当前未启用");
        }
        if (voucherId == null) {
            return Result.fail("优惠券ID不能为空");
        }
        if (!rateLimiter.tryAcquire()) {
            return Result.fail("当前请求较多，请稍后重试");
        }

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("秒杀优惠券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            return Result.fail("秒杀时间配置无效");
        }
        if (now.isBefore(voucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        if (now.isAfter(voucher.getEndTime())) {
            return Result.fail("秒杀已经结束");
        }

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long scriptResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                Long.toString(orderId),
                Long.toString(System.currentTimeMillis())
        );
        if (scriptResult == null) {
            return Result.fail("秒杀服务暂时不可用");
        }
        if (scriptResult != 0L) {
            return Result.fail(scriptResult == 1L ? "库存不足" : "该用户重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);
        mqSender.sendSeckillMessage(voucherOrder);
        return Result.ok(Long.toString(orderId));
    }

    /**
     * Persists one order and decrements database stock in one transaction.
     *
     * @return {@code false} when this user-voucher order already exists
     */
    @Override
    @Transactional
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        orderResolutionService.authorize(voucherOrder);
        VoucherOrder sameOrder = getById(voucherOrder.getId());
        if (sameOrder != null) {
            if (!Objects.equals(sameOrder.getUserId(), voucherOrder.getUserId())
                    || !Objects.equals(sameOrder.getVoucherId(), voucherOrder.getVoucherId())) {
                throw new IllegalArgumentException("Order identity mismatch");
            }
            orderResolutionService.committed(voucherOrder);
            return true; // Redelivery after DB commit must COMPLETE, never restore stock as a duplicate.
        }
        long existingOrders = lambdaQuery()
                .eq(VoucherOrder::getUserId, voucherOrder.getUserId())
                .eq(VoucherOrder::getVoucherId, voucherOrder.getVoucherId())
                .count();
        if (existingOrders > 0) {
            return false;
        }

        // Insert first. The database unique index is the final guard for concurrent duplicate messages.
        if (!save(voucherOrder)) {
            throw new IllegalStateException("Failed to persist voucher order " + voucherOrder.getId());
        }

        boolean stockDeducted = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!stockDeducted) {
            throw new IllegalStateException(
                    "Database stock is unavailable for voucher " + voucherOrder.getVoucherId());
        }
        orderResolutionService.committed(voucherOrder);
        return true;
    }
}
