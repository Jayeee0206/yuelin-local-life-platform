package com.yuelin.service;

import com.yuelin.dto.Result;
import com.yuelin.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopTypeService extends IService<ShopType> {

    Result queryList();
}
