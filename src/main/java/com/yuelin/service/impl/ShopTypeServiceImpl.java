package com.yuelin.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.dto.Result;
import com.yuelin.entity.ShopType;
import com.yuelin.mapper.ShopTypeMapper;
import com.yuelin.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.yuelin.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    private static final DefaultRedisScript<Long> FILL_CACHE_SCRIPT;

    static {
        FILL_CACHE_SCRIPT = new DefaultRedisScript<>();
        FILL_CACHE_SCRIPT.setLocation(new ClassPathResource("shop_type_cache_fill.lua"));
        FILL_CACHE_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        try {
            List<String> cached = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
            if (cached != null && !cached.isEmpty()) {
                List<ShopType> types = cached.stream()
                        .map(json -> JSONUtil.toBean(json, ShopType.class))
                        .collect(Collectors.toList());
                return Result.ok(types);
            }
        } catch (RuntimeException cacheFailure) {
            log.warn("Shop type cache unavailable or invalid; falling back to database", cacheFailure);
            try {
                stringRedisTemplate.delete(CACHE_SHOP_TYPE_KEY);
            } catch (RuntimeException cleanupFailure) {
                cacheFailure.addSuppressed(cleanupFailure);
            }
        }

        List<ShopType> types = query().orderByAsc("sort").list();
        if (types == null || types.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<String> jsonValues = types.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        // The existence check and all RPUSH operations run in one Redis command, so
        // concurrent cold-cache requests cannot append duplicate categories.
        try {
            stringRedisTemplate.execute(
                    FILL_CACHE_SCRIPT,
                    Collections.singletonList(CACHE_SHOP_TYPE_KEY),
                    jsonValues.toArray(new String[0]));
        } catch (RuntimeException cacheFailure) {
            log.warn("Shop types loaded from database but cache fill failed", cacheFailure);
        }
        return Result.ok(types);
    }
}
