package com.yuelin.service;

import com.yuelin.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.yuelin.utils.RedisConstants.SECKILL_RESERVATION_PENDING_KEY;
import static com.yuelin.utils.RedisConstants.SECKILL_RESERVATION_TIMEOUT_KEY;

/**
 * Completes, retries or compensates Redis reservations created by seckill.lua.
 * State transitions that change stock are idempotent Lua scripts.
 */
@Slf4j
@Service
public class SeckillReservationService {

    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = script("seckill_compensate.lua");
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = script("seckill_complete.lua");
    private static final DefaultRedisScript<Long> REJECT_DUPLICATE_SCRIPT = script("seckill_reject_duplicate.lua");
    private static final DefaultRedisScript<Long> CLAIM_RETRY_SCRIPT = script("seckill_claim_retry.lua");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderResolutionService orderResolutionService;

    public void complete(VoucherOrder order) {
        stringRedisTemplate.execute(
                COMPLETE_SCRIPT,
                Collections.emptyList(),
                order.getId().toString());
    }

    public void rejectDuplicate(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(
                REJECT_DUPLICATE_SCRIPT,
                Collections.emptyList(),
                order.getVoucherId().toString(),
                order.getUserId().toString(),
                order.getId().toString());
        if (result != null && result == 1L) {
            log.warn("Rejected duplicate seckill reservation and restored Redis stock: orderId={}, userId={}, voucherId={}",
                    order.getId(), order.getUserId(), order.getVoucherId());
        } else if (result != null && result == -1L) {
            log.error("Refused mismatched duplicate reservation: orderId={}, userId={}, voucherId={}",
                    order.getId(), order.getUserId(), order.getVoucherId());
        } else if (result != null && result == -2L) {
            log.error("Duplicate reservation cleaned while stock key was missing; audit will recover it: orderId={}, voucherId={}",
                    order.getId(), order.getVoucherId());
        }
    }

    public void compensate(VoucherOrder order, String reason) {
        if (!orderResolutionService.cancel(order)) {
            complete(order); // A committed order wins over uncertain publisher/reconciler outcomes.
            return;
        }
        Long result = stringRedisTemplate.execute(
                COMPENSATE_SCRIPT,
                Collections.emptyList(),
                order.getVoucherId().toString(),
                order.getUserId().toString(),
                order.getId().toString());
        if (result != null && result == 1L) {
            log.warn("Compensated seckill reservation: orderId={}, userId={}, voucherId={}, reason={}",
                    order.getId(), order.getUserId(), order.getVoucherId(), reason);
        } else if (result != null && result == -1L) {
            log.error("Refused mismatched seckill compensation: orderId={}, userId={}, voucherId={}, reason={}",
                    order.getId(), order.getUserId(), order.getVoucherId(), reason);
        } else if (result != null && result == -2L) {
            log.error("Reservation compensated while stock key was missing; audit will recover it: orderId={}, voucherId={}",
                    order.getId(), order.getVoucherId());
        }
    }

    public List<Long> findTimedOutOrderIds(long cutoffEpochMillis, int limit) {
        Set<String> orderIds = stringRedisTemplate.opsForZSet().rangeByScore(
                SECKILL_RESERVATION_TIMEOUT_KEY,
                0,
                cutoffEpochMillis,
                0,
                limit);
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>(orderIds.size());
        for (String orderId : orderIds) {
            try {
                result.add(Long.parseLong(orderId));
            } catch (NumberFormatException malformedId) {
                log.error("Removing malformed seckill reservation id: {}", orderId);
                stringRedisTemplate.opsForZSet().remove(SECKILL_RESERVATION_TIMEOUT_KEY, orderId);
            }
        }
        return result;
    }

    public Optional<VoucherOrder> findPendingOrder(Long orderId) {
        Object raw = stringRedisTemplate.opsForHash().get(
                SECKILL_RESERVATION_PENDING_KEY,
                orderId.toString());
        if (raw == null) {
            stringRedisTemplate.opsForZSet().remove(
                    SECKILL_RESERVATION_TIMEOUT_KEY,
                    orderId.toString());
            return Optional.empty();
        }
        String[] parts = raw.toString().split(":", 2);
        if (parts.length != 2) {
            log.error("Malformed seckill reservation payload: orderId={}, payload={}", orderId, raw);
            return Optional.empty();
        }
        try {
            return Optional.of(new VoucherOrder()
                    .setId(orderId)
                    .setVoucherId(Long.parseLong(parts[0]))
                    .setUserId(Long.parseLong(parts[1])));
        } catch (NumberFormatException malformedPayload) {
            log.error("Malformed seckill reservation payload: orderId={}, payload={}", orderId, raw);
            return Optional.empty();
        }
    }

    /**
     * Atomically claims one timed-out reservation for retry across multiple service instances.
     *
     * @return retry attempt, 0 if another instance already claimed it, -1 if no reservation exists
     */
    public long claimRetry(Long orderId, long cutoffEpochMillis, long nextCheckEpochMillis) {
        Long attempt = stringRedisTemplate.execute(
                CLAIM_RETRY_SCRIPT,
                Collections.emptyList(),
                orderId.toString(),
                Long.toString(cutoffEpochMillis),
                Long.toString(nextCheckEpochMillis));
        return attempt == null ? 0L : attempt;
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
