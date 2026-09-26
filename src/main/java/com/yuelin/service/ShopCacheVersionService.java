package com.yuelin.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

@Service
public class ShopCacheVersionService {
    @Resource
    private JdbcTemplate jdbcTemplate;

    public long current(Long shopId) {
        List<Long> rows = jdbcTemplate.queryForList("SELECT version FROM tb_shop_cache_version WHERE shop_id=?", Long.class, shopId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void advance(Long shopId) {
        int changed = jdbcTemplate.update("INSERT INTO tb_shop_cache_version (shop_id,version) VALUES (?,1) "
                + "ON DUPLICATE KEY UPDATE version=version+1", shopId);
        if (changed < 1) { throw new IllegalStateException("Failed to advance shop cache version"); }
    }
}
