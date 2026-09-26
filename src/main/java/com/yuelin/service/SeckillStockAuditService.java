package com.yuelin.service;

import com.yuelin.entity.SeckillVoucher;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.yuelin.utils.RedisConstants.SECKILL_RESERVATION_PENDING_COUNT_KEY;
import static com.yuelin.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * Audits the invariant: Redis available stock = database stock - pending reservations.
 * Existing live values are reported, never overwritten. A missing key is restored only
 * when an atomic Redis check confirms there are no in-flight reservations.
 */
@Slf4j
@Service
public class SeckillStockAuditService {
    private static final int MISSING_STOCK = Integer.MIN_VALUE;
    private static final DefaultRedisScript<Long> RECOVER_MISSING_SCRIPT;

    static {
        RECOVER_MISSING_SCRIPT = new DefaultRedisScript<>();
        RECOVER_MISSING_SCRIPT.setLocation(new ClassPathResource("seckill_stock_recover.lua"));
        RECOVER_MISSING_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Scheduled(
            initialDelayString = "${yuelin.seckill.audit.initial-delay-ms:60000}",
            fixedDelayString = "${yuelin.seckill.audit.interval-ms:60000}")
    public void scheduledAudit() {
        for (StockDiscrepancy discrepancy : findDiscrepancies()) {
            log.error("Seckill stock discrepancy: voucherId={}, databaseStock={}, pending={}, expectedRedisStock={}, actualRedisStock={}",
                    discrepancy.getVoucherId(),
                    discrepancy.getDatabaseStock(),
                    discrepancy.getPendingReservations(),
                    discrepancy.getExpectedRedisStock(),
                    discrepancy.getActualRedisStock());
        }
    }

    public List<StockDiscrepancy> findDiscrepancies() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        if (vouchers == null || vouchers.isEmpty()) {
            return Collections.emptyList();
        }

        List<StockDiscrepancy> discrepancies = new ArrayList<>();
        for (SeckillVoucher listed : vouchers) {
            Long voucherId = listed.getVoucherId();
            Object pendingValue = stringRedisTemplate.opsForHash().get(
                    SECKILL_RESERVATION_PENDING_COUNT_KEY,
                    voucherId.toString());
            int pending = parseInt(pendingValue, 0);
            String redisStockValue = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
            SeckillVoucher authoritative = listed;

            if (redisStockValue == null && pending == 0) {
                // Re-read after detecting the hole so a stale audit snapshot cannot republish old stock.
                authoritative = seckillVoucherService.getById(voucherId);
                if (authoritative != null) {
                    int latestStock = stock(authoritative);
                    Long recovered = stringRedisTemplate.execute(
                            RECOVER_MISSING_SCRIPT,
                            Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_RESERVATION_PENDING_COUNT_KEY),
                            voucherId.toString(),
                            Integer.toString(latestStock));
                    if (recovered != null && recovered == 1L) {
                        log.warn("Recovered missing seckill stock key from database: voucherId={}, stock={}",
                                voucherId, latestStock);
                        continue;
                    }
                    // Another publisher may have won SETNX while this audit was running.
                    redisStockValue = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
                }
            }

            int databaseStock = stock(authoritative);
            int expectedRedisStock = databaseStock - pending;
            int actualRedisStock = parseInt(redisStockValue, MISSING_STOCK);
            if (actualRedisStock != expectedRedisStock) {
                discrepancies.add(new StockDiscrepancy(
                        voucherId,
                        databaseStock,
                        pending,
                        expectedRedisStock,
                        actualRedisStock));
            }
        }
        return discrepancies;
    }

    private int stock(SeckillVoucher voucher) {
        return voucher == null || voucher.getStock() == null ? 0 : voucher.getStock();
    }

    private int parseInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException invalidNumber) {
            return fallback;
        }
    }

    @Data
    @AllArgsConstructor
    public static class StockDiscrepancy {
        private Long voucherId;
        private int databaseStock;
        private int pendingReservations;
        private int expectedRedisStock;
        private int actualRedisStock;
    }
}
