package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPTS;
    static {
        UNLOCK_SCRIPTS=new DefaultRedisScript<>();
        UNLOCK_SCRIPTS.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPTS.setResultType(Long.class);
    }

    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate) {
        this.name=name;
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeOutSec) {
        String value = ID_PREFIX+Thread.currentThread().getId();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, value, timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
        //或者用
//        return BooleanUtil.isTrue(flag);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPTS,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId());


//        String value = ID_PREFIX+Thread.currentThread().getId();
//        if(value.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + name))){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
    }
}