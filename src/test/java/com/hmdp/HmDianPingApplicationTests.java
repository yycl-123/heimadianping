package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private IShopService shopService;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private RedisWorker redisWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void yuRe() throws InterruptedException {
        Shop shop = shopService.getById(2L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+2L,shop,10L, TimeUnit.SECONDS);
    }

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testNextId() throws InterruptedException {

        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for(int i=0;i<100;i++){
                long id = redisWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i=0;i<300;i++){
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end-begin));
    }

    @Test
    public void testTypeId(){
        //1.查询店铺数据
        List<Shop> list = shopService.list();

        //2.将店铺分组，同一个typeId放入同一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        //3.分批完成写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1 得到typeId
            Long typeId = entry.getKey();
            String key="shop:geo:"+typeId;

            //3.2 得到店铺数据
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());


            //3.3写入redis geoadd key 经度 纬度 member
            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(), shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }


    }

    @Test
    public void testUV(){
        String[] values=new String[1000];
        for(int i=0;i<1000000;i++){
            int j=i%1000;
            values[j]="user_"+i;
            if(j==999){
                stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }

}
