package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(ID id, String cacheShopKeyPrefix, Class<R> type, Function<ID,R> dbFallBack,Long cacheShopTime,TimeUnit unit) {
        String key=cacheShopKeyPrefix+id;
        //先从redis中查询商铺缓存
        String jsonString = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(jsonString)){
            //若缓存中命中则直接返回
            R r = JSONUtil.toBean(jsonString, type);
            return r;
        }
        if(jsonString!=null){
            return null;
        }
        //未命中，先查数据库
        R r = dbFallBack.apply(id);
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在，将商铺数据写入redis
        this.set(key,r,cacheShopTime,unit);
        //返回商铺信息
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(ID id,String cacheShopKeyPrefix,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        String key=cacheShopKeyPrefix+id;
        //先从redis中查询商铺缓存
        String jsonString = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(jsonString)){
            //若缓存未命中直接返回空
            return null;
        }
        //若缓存命中，则判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(jsonString, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期，返回商铺信息
            return r;
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
                    return JSONUtil.toBean(latestData, type);
                }
            }
            //开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.execute(()->{
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                    Thread.sleep(200);//模拟重建延时
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(id);
                }
            });
        }
        return r;
    }

    public <ID> boolean tryLock(ID id){
        String key=LOCK_SHOP_KEY+id;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public <ID> void unLock(ID id){
        stringRedisTemplate.delete(LOCK_SHOP_KEY+id);
    }
}