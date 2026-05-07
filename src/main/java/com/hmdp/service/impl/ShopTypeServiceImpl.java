package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPLIST_KEY;
import static com.hmdp.utils.RedisConstants.SHOPLIST_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result selectShopList() {
        //todo 学习如何画简易流程图
        //首先从redis中获取数据
        List<String> shopList = stringRedisTemplate.opsForList().range(CACHE_SHOPLIST_KEY, 0, -1);
        //若查询到数据直接返回
        if(shopList!=null&&!shopList.isEmpty()){
            List<ShopType> shopTypeList=new ArrayList<>();
            for (String shop : shopList) {
                shopTypeList.add(JSONUtil.toBean(shop,ShopType.class));
            }
            return Result.ok(shopTypeList);
        }
        //若没有查询到，则去数据库中查询
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //若数据库中也查询不到，则直接返回错误
        if(shopTypeList==null||shopTypeList.isEmpty()){
            return Result.fail("数据库中没有商铺列表数据");
        }
        //保存进redis中
        shopList= shopTypeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOPLIST_KEY,shopList);
        stringRedisTemplate.expire(CACHE_SHOPLIST_KEY,SHOPLIST_TTL, TimeUnit.DAYS);
        return Result.ok(shopList);
    }
}
