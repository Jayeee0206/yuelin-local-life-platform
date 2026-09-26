package com.yuelin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuelin.dto.Result;
import com.yuelin.entity.Shop;
import com.yuelin.mapper.ShopMapper;
import com.yuelin.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.utils.CacheClient;
import com.yuelin.service.ShopCacheVersionService;
import com.yuelin.service.ShopGeoIndexService;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import lombok.extern.slf4j.Slf4j;
import com.yuelin.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.yuelin.utils.RedisConstants.*;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private ShopCacheVersionService cacheVersions;

    @Autowired
    private ShopGeoIndexService geoIndexService;

    @Override
    public Result queryById(Long id) {
        if (id == null || id <= 0) { return Result.fail("店铺ID无效"); }
        long version = cacheVersions.current(id);
        Shop shop;
        try {
            shop = cacheClient.queryWithPassThrough(
                    CACHE_SHOP_KEY + version + ":", id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (RedisConnectionFailureException | RedisSystemException unavailable) {
            log.warn("Shop cache unavailable; falling back to database for shop {}", id);
            shop = getById(id);
        }
        if (shop == null) {
            return Result.fail("店铺不存在！！");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result create(Shop shop) {
        if (shop == null) {
            return Result.fail("店铺信息不能为空");
        }
        shop.setId(null);
        if (!isValidGeo(shop)) {
            return Result.fail("店铺类型或坐标无效");
        }
        if (baseMapper.insert(shop) != 1) {
            return Result.fail("新增店铺失败");
        }
        geoIndexService.syncAfterCommit(shop, null);
        return Result.ok(String.valueOf(shop.getId()));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getId() <= 0) {
            return Result.fail("店铺id不能为空！！");
        }
        Shop before = getById(shop.getId());
        if (before == null) {
            return Result.fail("店铺不存在或更新失败");
        }
        Shop indexed = mergeGeo(before, shop);
        if (!isValidGeo(indexed)) {
            return Result.fail("店铺类型或坐标无效");
        }
        if (!updateById(shop)) {
            return Result.fail("店铺不存在或更新失败");
        }
        // The generation becomes visible atomically with the row update at commit.
        // A read started before this commit can only populate an expiring cache generation.
        cacheVersions.advance(shop.getId());
        geoIndexService.syncAfterCommit(indexed, before.getTypeId());
        return Result.ok();
    }

    private Shop mergeGeo(Shop before, Shop update) {
        return new Shop()
                .setId(before.getId())
                .setTypeId(update.getTypeId() == null ? before.getTypeId() : update.getTypeId())
                .setX(update.getX() == null ? before.getX() : update.getX())
                .setY(update.getY() == null ? before.getY() : update.getY());
    }

    private boolean isValidGeo(Shop shop) {
        return shop.getTypeId() != null && shop.getTypeId() > 0
                && shop.getX() != null && shop.getX() >= -180 && shop.getX() <= 180
                && shop.getY() != null && shop.getY() >= -90 && shop.getY() <= 90;
    }

    // Prewarm the same finite-TTL JSON format used by queryById, never a logical-expiry envelope.
    public void saveShop2Redis(Long id, Long expirSeconds) {
        long version = cacheVersions.current(id);
        Shop shop = getById(id);
        cacheClient.set(CACHE_SHOP_KEY + version + ":" + id, shop, expirSeconds, TimeUnit.SECONDS);
    }

    @Override
    public Result queryShopByType(Long typeId, Integer current, Double x, Double y, String sortBy) {
        if (typeId == null || typeId <= 0) {
            return Result.fail("商铺类型ID无效");
        }
        int pageNumber = current == null || current < 1 ? 1 : current;
        // 没有坐标时按数据库分页；仅允许前端展示的两个白名单字段参与排序。
        if (x == null || y == null) {
            com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper<Shop> wrapper = query().eq("type_id", typeId);
            if ("comments".equals(sortBy)) {
                wrapper.orderByDesc("comments");
            } else if ("score".equals(sortBy)) {
                wrapper.orderByDesc("score");
            }
            Page<Shop> page = wrapper.page(new Page<>(pageNumber, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
//        以下是需要根据距离查询

        //2. 计算分页查询参数
        int from = (pageNumber - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = pageNumber * SystemConstants.DEFAULT_PAGE_SIZE;


        if (x < -180 || x > 180 || y < -90 || y > 90) {
            return Result.fail("查询坐标无效");
        }
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results;
        try {
            results = stringRedisTemplate.opsForGeo().search(key,
                    GeoReference.fromCoordinate(x, y),
                    new Distance(5000),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        } catch (RuntimeException unavailable) {
            log.warn("Shop GEO index unavailable; falling back to database for type {}", typeId, unavailable);
            return queryNearbyFromDatabase(typeId, pageNumber, x, y);
        }

        if (results == null || results.getContent().isEmpty()) {
            return queryNearbyFromDatabase(typeId, pageNumber, x, y);
        }

        //4. 解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        if (list.size() <= from) {
            //起始查询位置大于数据总量，则说明没数据了，返回空集合
            return Result.ok(Collections.emptyList());
        }

        ArrayList<Long> ids = new ArrayList<>(list.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        //5. 根据id查询shop
        String idsStr = StrUtil.join(",", ids);

        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD( id," + idsStr + ")").list();
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        //6. 返回
        return Result.ok(shops);
    }

    private Result queryNearbyFromDatabase(Long typeId, int pageNumber, double x, double y) {
        List<Shop> loaded = query().eq("type_id", typeId).list();
        if (loaded == null || loaded.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Shop> candidates = new ArrayList<>(loaded);
        candidates.removeIf(shop -> shop.getX() == null || shop.getY() == null);
        for (Shop shop : candidates) {
            shop.setDistance(distanceMeters(x, y, shop.getX(), shop.getY()));
        }
        candidates.removeIf(shop -> shop.getDistance() > 5000);
        candidates.sort(Comparator.comparingDouble(Shop::getDistance));
        int from = (pageNumber - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        if (from >= candidates.size()) {
            return Result.ok(Collections.emptyList());
        }
        int to = Math.min(from + SystemConstants.DEFAULT_PAGE_SIZE, candidates.size());
        return Result.ok(new ArrayList<>(candidates.subList(from, to)));
    }

    private double distanceMeters(double longitudeA, double latitudeA, double longitudeB, double latitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 6371000D * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
