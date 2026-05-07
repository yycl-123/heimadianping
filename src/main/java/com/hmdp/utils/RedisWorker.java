package com.hmdp.utils;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {

    private static final long basicTime=1664582400L;
    private static final long COUNT_BITS=32;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){
        //生成时间戳
        long now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timeMills=now-basicTime;

        String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        //生成序列号
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + format);

        return timeMills<<COUNT_BITS|increment;
    }

    public static void main(String[] args) {
        LocalDateTime localDateTime = LocalDateTime.of(2022, 10, 1, 0, 0, 0);
        long basicTime = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println("basicTime = " + basicTime);
    }
    
}