package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    public Result queryById(Long id){
        //只解决缓存穿透方案
//        Shop shop = cacheClient.queryWithPassThrough(id, CACHE_SHOP_KEY, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);

        //利用互斥锁解决缓存穿透和缓存击穿方案
//        Shop shop = queryWithMutex(id);
        //利用逻辑过期解决缓存击穿
//        Shop shop= queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(id, CACHE_SHOP_KEY, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
        if(shop==null){
            return Result.fail("店铺信息不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //先从redis中查询商铺缓存
        String shopString = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopString)){
            //若缓存未命中直接返回空
            return null;
        }
        //若缓存命中，则判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopString, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回商铺信息
            return shop;
        }
        //已过期，尝试获取互斥锁
        boolean b = tryLock(id);
        if(b){
            //成功获取互斥锁
            // 再次查询 Redis，确认缓存是否真的还需要重建
            String doubleCheckJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(doubleCheckJson)) {
                RedisData doubleCheckData = JSONUtil.toBean(doubleCheckJson, RedisData.class);
                // 如果此时缓存已经未过期，说明其他线程已经重建好了，无需再重建
                if (doubleCheckData.getExpireTime().isAfter(LocalDateTime.now())) {
                    unLock(id); // 释放锁
                    // 直接返回最新缓存中的店铺数据
                    JSONObject latestData = (JSONObject) doubleCheckData.getData();
                    return JSONUtil.toBean(latestData, Shop.class);
                }
            }
            //开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.execute(()->{
                try {
                    this.rebuildRedis(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(id);
                }
            });
        }
        return shop;
    }

    public void rebuildRedis(Long id,Long expireTime) throws InterruptedException {
        String key= CACHE_SHOP_KEY+id;
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);//模拟重建延时
        //todo 需要判断shop是否为空吗？
        //封装逻辑过期时间
        RedisData redisData=new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        redisData.setData(shop);
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }


    public Shop queryWithMutex(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //先从redis中查询商铺缓存
        String shopString = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopString)){
            //若缓存中命中则直接返回
            Shop shop = JSONUtil.toBean(shopString, Shop.class);
            return shop;
        }
        if(shopString!=null){//说明redis中存空串
            return null;
        }
        Shop shop=null;
        try {
            //未命中，尝试获取互斥锁
            boolean b = tryLock(id);
            //若未获得成功，则休眠重试
            if(!b){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //若获取成功，则查询数据库重建缓存
            shop = getById(id);
            Thread.sleep(200);
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL+RandomUtil.randomLong(0,5),TimeUnit.MINUTES);
                return null;
            }
            //模拟重建延时
            Thread.sleep(200);
            shopString= JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key,shopString,CACHE_SHOP_TTL+ RandomUtil.randomLong(0,5), TimeUnit.MINUTES);//缓存时间是30分钟+随机值
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(id);
        }
        //返回数据
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        String key=CACHE_SHOP_KEY+id;
        //先从redis中查询商铺缓存
        String shopString = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopString)){
            //若缓存中命中则直接返回
            Shop shop = JSONUtil.toBean(shopString, Shop.class);
            return shop;
        }
        if(shopString!=null){
            return null;
        }
        //未命中，先查数据库
        Shop shop = getById(id);
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL+RandomUtil.randomLong(0,5),TimeUnit.MINUTES);//添加随机值防止缓存雪崩
            return null;
        }
        //存在，将商铺数据写入redis
        shopString= JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,shopString,CACHE_SHOP_TTL+ RandomUtil.randomLong(0,5), TimeUnit.MINUTES);//缓存时间是30分钟
        //返回商铺信息
        return shop;
    }

    public boolean tryLock(Long id){
        String key=LOCK_SHOP_KEY+id;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void unLock(Long id){
        stringRedisTemplate.delete(LOCK_SHOP_KEY+id);
    }

    @Override
    public Result updateShopBydeleting(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("id不能为空");
        }
        //先操作数据库
        updateById(shop);
        //再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //如果x或y不存在，则直接去数据库查询
        if(x==null||y==null){
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key=SHOP_GEO_KEY+typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));

        //解析出id
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        if(list.size()<=from){
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }

        //截取从from到to的部分
        List<Long> ids= new ArrayList<>(list.size());
        Map<String,Distance> map=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{

            //店铺数据
            String idStr = result.getContent().getName();
            ids.add(Long.valueOf(idStr));
            Distance distance = result.getDistance();
            map.put(idStr,distance);
        });

        String idStrs = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStrs + ")").list();

        for (Shop shop : shops) {
            //店铺数据加上距离
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);

    }
}
