package com.yuelin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuelin.entity.Shop;
import com.yuelin.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.yuelin.utils.RedisConstants.SHOP_GEO_KEY;

/** Maintains the derived Redis GEO index without coupling its availability to DB commits. */
@Slf4j
@Service
public class ShopGeoIndexService {
    private static final DefaultRedisScript<Long> SYNC_SCRIPT = script("shop_geo_sync.lua");
    private static final DefaultRedisScript<Long> REBUILD_SCRIPT = script("shop_geo_rebuild.lua");

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;
    @Resource
    private ShopMapper shopMapper;

    public void syncAfterCommit(Shop shop, Long previousTypeId) {
        Shop snapshot = copyIndexFields(shop);
        Runnable action = () -> safeSync(snapshot, previousTypeId);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    void safeSync(Shop shop, Long previousTypeId) {
        try {
            validate(shop);
            boolean moved = previousTypeId != null && !previousTypeId.equals(shop.getTypeId());
            String previousKey = SHOP_GEO_KEY + (moved ? previousTypeId : shop.getTypeId());
            String currentKey = SHOP_GEO_KEY + shop.getTypeId();
            redisTemplate.execute(
                    SYNC_SCRIPT,
                    Arrays.asList(previousKey, currentKey),
                    shop.getId().toString(),
                    shop.getX().toString(),
                    shop.getY().toString(),
                    moved ? "1" : "0");
        } catch (RuntimeException failure) {
            log.warn("Shop {} committed but GEO index synchronization failed", shop == null ? null : shop.getId(), failure);
        }
    }

    /** Atomically replaces one type's GEO key from the authoritative database rows. */
    public long rebuildType(Long typeId) {
        if (typeId == null || typeId <= 0) {
            throw new IllegalArgumentException("商铺类型ID无效");
        }
        List<Shop> shops = shopMapper.selectList(
                new LambdaQueryWrapper<Shop>().eq(Shop::getTypeId, typeId));
        List<String> arguments = new ArrayList<>((shops == null ? 0 : shops.size()) * 3);
        if (shops != null) {
            for (Shop shop : shops) {
                validate(shop);
                arguments.add(shop.getId().toString());
                arguments.add(shop.getX().toString());
                arguments.add(shop.getY().toString());
            }
        }
        String target = SHOP_GEO_KEY + typeId;
        String temporary = target + ":rebuild:" + UUID.randomUUID();
        Long count = redisTemplate.execute(
                REBUILD_SCRIPT,
                Arrays.asList(target, temporary),
                arguments.toArray(new String[0]));
        return count == null ? 0 : count;
    }

    private static DefaultRedisScript<Long> script(String resource) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resource));
        script.setResultType(Long.class);
        return script;
    }

    private Shop copyIndexFields(Shop source) {
        if (source == null) {
            return null;
        }
        return new Shop()
                .setId(source.getId())
                .setTypeId(source.getTypeId())
                .setX(source.getX())
                .setY(source.getY());
    }

    private void validate(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getId() <= 0
                || shop.getTypeId() == null || shop.getTypeId() <= 0
                || shop.getX() == null || shop.getX() < -180 || shop.getX() > 180
                || shop.getY() == null || shop.getY() < -90 || shop.getY() > 90) {
            throw new IllegalArgumentException("商铺GEO信息无效");
        }
    }
}
