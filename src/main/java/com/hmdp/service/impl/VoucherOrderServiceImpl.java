package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;


    /**
     * 初始化任务
     */
    @PostConstruct
    private void init(){
        //todo
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 阻塞队列
     */
//    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);

    /**
     * 线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();


    /**
     * 线程任务：不断从阻塞队列中获取订单
     */
    private class VoucherOrderHandler implements Runnable{
        String queneName = "stream.orders";
        @Override
        public void run(){
            while (true){
                try {
                    //读取消息队列中的订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queneName, ReadOffset.lastConsumed())
                    );
                    //判断有无订单数据，没有则continue
                    if(list==null||list.isEmpty()){
                        continue;
                    }
                    //获取订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queneName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try {
                    //读取消息队列中的订单
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queneName, ReadOffset.from("0"))
                    );
                    //判断有无订单数据，没有则continue
                    if(list==null||list.isEmpty()){
                        break;
                    }
                    //获取订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //获取成功，可以下单
                    handleVoucherOrder(voucherOrder);

                    //ack确认
                    stringRedisTemplate.opsForStream().acknowledge(queneName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        log.error("线程休眠异常", ex);
                    }
                }
            }
        }
    }

//    /**
//     * 线程任务：不断从阻塞队列中获取订单
//     */
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run(){
//            while (true){
//                //从阻塞队列中获取订单信息，并创建订单
//                try {
//                    VoucherOrder voucherOrder=orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }

    /**
     * 创建订单
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
//        synchronized (userId.toString().intern()){
//            //获取代理对象，使事务生效
//            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        }
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取锁失败，直接返回失败信息
            log.error("一人只能下一单");
        }
        try {
            proxy.createOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            lock.unlock();
        }
    }


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    @Override
    public Result createNewOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisWorker.nextId("order");
        //执行Lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(),orderId.toString());
        int r = result.intValue();
        //判断结果是否为0
        if(r!=0){
            //不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"用户不能重复下单");
        }
        //创建订单id
        //保存阻塞队列
        //创建订单
//        VoucherOrder voucherOrder=new VoucherOrder();
//        //设置用户id
//        voucherOrder.setUserId(userId);
//        //设置优惠券id
//        voucherOrder.setVoucherId(voucherId);
//        //设置订单id
//        long orderId = redisWorker.nextId("order");
//        voucherOrder.setId(orderId);
//
//        orderTasks.add(voucherOrder);
        IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
        this.proxy=proxy;
        //返回订单id
        return Result.ok(orderId);
    }


//    @Override
//    public Result createNewOrder(Long voucherId) {
//        //查询优惠券信息
//        SeckillVoucher byId = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if(byId.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        //判断秒杀是否结束
//        if(LocalDateTime.now().isAfter(byId.getEndTime())){
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if(byId.getStock()<1){
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()){
////            //获取代理对象，使事务生效
////            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createOrder(voucherId);
////        }
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("用户已经下单，请不要再下单");
//        }
//        try {
//            //获取代理对象，使事务生效
//            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//    }

    //新建订单（数据库操作）
    @Transactional
    public void createOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //判断订单是否存在，若存在直接返回false
        int count = query().eq("voucher_id", voucherOrder.getVoucherId())
                .eq("user_id", userId).count();
        if(count>0){
            log.error("用户已经下过单");
            return ;
        }
        //扣减库存
        boolean success = seckillVoucherService.update().setSql(" stock = stock - 1")//set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId())//voucher_id=voucherId
                .gt("stock", 0)//where voucher_id=voucherId and stock>0
                .update();
        if(!success){
            log.error("扣减失败");
            return ;
        }

//        //创建订单
//        VoucherOrder voucherOrder=new VoucherOrder();
//        //设置用户id
//        voucherOrder.setUserId(userId);
//        //设置优惠券id
//        voucherOrder.setVoucherId(voucherId);
//        //设置订单id
//        long orderId = redisWorker.nextId("order");
//        voucherOrder.setId(orderId);
        //新建订单
        save(voucherOrder);
//        //返回订单id
//        return Result.ok(voucherOrder.g);
    }
}
