package com.yuelin.service;

import com.yuelin.dto.Result;
import com.yuelin.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result create(Shop shop);

    Result update(Shop shop);

    Result queryShopByType(Long typeId, Integer current, Double x, Double y, String sortBy);
}
