# 黑马点评 (HeiMa DianPing)

基于 Spring Boot + Redis 的本地生活服务点评平台后端服务，覆盖 **商户查询、优惠券秒杀、探店博客、社交关注、签到统计** 等核心功能。重点实践了 Redis 在高并发场景下的多种应用：缓存穿透/击穿/雪崩解决方案、分布式锁、消息队列、地理位置搜索、BitMap 签到、Feed 流滚动分页等。

## 技术栈

| 类别     | 技术                                        |
| -------- | ------------------------------------------- |
| 核心框架 | Spring Boot 2.3.12                          |
| ORM      | MyBatis-Plus 3.4.3（分页 + 条件构造器）     |
| 数据库   | MySQL 5.7                                   |
| 缓存     | Redis（Lettuce 连接池 + Spring Data Redis） |
| 分布式锁 | Redisson 3.13.6 + 自定义 Lua 原子锁         |
| 工具库   | Hutool 5.7、Lombok、AspectJ                 |
| 消息队列 | Redis Stream（消费者组 + ACK 确认）         |
| 语言     | Java 8                                      |

## 架构设计

```
┌─────────────────────────────────────────────┐
│                  Client                      │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│      Interceptor (Token 刷新 + 登录鉴权)      │
├──────────┬──────────┬──────────┬────────────┤
│  User    │  Shop    │  Blog    │  Voucher   │
│  用户    │  商户    │  探店    │  秒杀      │
├──────────┴──────────┴──────────┴────────────┤
│              Service 业务层                   │
├──────────┬──────────┬──────────┬────────────┤
│          │ CacheClient (通用缓存)            │
│  MySQL   │ Redis String / Hash / Set / ZSet  │
│   持久层  │ Geo / BitMap / Stream / Lua       │
└──────────┴──────────────────────────────────┘
```

## 功能模块

### 用户模块

- 手机号 + 验证码登录，Token 存储于 Redis Hash
- 双拦截器设计（`RefreshTokenInterceptor` 自动续期 + `LoginInterceptor` 鉴权）
- ThreadLocal 保存用户上下文，请求结束自动清理避免内存泄漏

### 商户模块

- 缓存穿透：缓存空值 + 短 TTL（2分钟）
- 缓存击穿：互斥锁（SETNX）与逻辑过期两种方案可切换
- 缓存雪崩：TTL 添加随机值 + 线程池异步重建
- 更新策略：先更新数据库，再删除缓存（Cache Aside Pattern）
- 通用缓存工具类 `CacheClient` 封装三种策略

### 地理位置搜索

- 基于 Redis Geo + Geohash 算法实现附近商户检索
- 支持按距离排序、5km 范围内商户筛选 + 内存分页

### 探店博客

- 点赞/取消点赞使用 Redis ZSet 存储（score = 时间戳）
- 点赞排行榜通过 ZSet Range 取 Top 5
- 发布博客时推送到所有粉丝收件箱（推模式 Feed 流）
- Feed 流采用滚动分页（ZSet ReverseRangeByScore），避免传统 offset 分页的数据重复/遗漏

### 优惠券秒杀 ⭐

- **Lua 原子化校验**：判库存 → 判重复 → 扣库存 → 记录用户 → 发送队列，五步合一
- **Redis Stream 异步下单**：消费者组 + ACK 确认 + Pending List 异常兜底
- **Redisson 分布式锁**：WatchDog 自动续期，防止一人多单
- **数据库乐观锁**：`WHERE stock > 0` 兜底防止超卖
- **全局 ID 生成器**：高 32 位时间戳 + 低 32 位 Redis INCR 序列号，无时钟回拨问题

### 社交功能

- 关注/取关同步 Redis Set，方便后续交集计算
- 共同关注通过 Redis Set INTERSECT 实现

### 用户签到

- Redis BitMap 存储每日签到状态（按月分组 key）
- BITFIELD 批量读取 + 位运算统计连续签到天数

## Redis 数据结构全景

| 场景         | Redis 类型 | Key 示例                    | 核心命令                         |
| ------------ | ---------- | --------------------------- | -------------------------------- |
| 短信验证码   | String     | `login:code:{phone}`        | SET + EX                         |
| Token 存储   | Hash       | `login:token:{uuid}`        | HSET + EXPIRE                    |
| 商户缓存     | String     | `cache:shop:{id}`           | SET + GET（含逻辑过期封装）      |
| 互斥锁       | String     | `lock:shop:{id}`            | SET NX EX + Lua DEL              |
| 秒杀库存     | String     | `seckill:stock:{voucherId}` | INCRBY（Lua 原子操作）           |
| 秒杀下单记录 | Set        | `seckill:order:{voucherId}` | SADD / SISMEMBER（Lua 原子操作） |
| 点赞数据     | ZSet       | `blog:liked:{blogId}`       | ADD / REMOVE / RANGE             |
| Feed 收件箱  | ZSet       | `feed:{userId}`             | ADD + REVERSE_RANGE_BY_SCORE     |
| 商户地理位置 | Geo        | `shop:geo:{typeId}`         | GEO ADD / SEARCH                 |
| 签到记录     | BitMap     | `sign:{userId}:yyyyMM`      | SETBIT / BITFIELD                |
| 关注列表     | Set        | `follow:{userId}`           | ADD / REMOVE / INTERSECT         |
| 秒杀消息队列 | Stream     | `stream.orders`             | XADD / XREAD / XACK              |
| 全局 ID 序列 | String     | `icr:{prefix}:yyyy:MM:dd`   | INCR                             |

## 秒杀链路演进

```
V1.0  纯数据库（库存直接扣减）
  └─ 问题：超卖（stock < 0）
       │
V2.0  synchronized 同步块
  └─ 问题：集群环境下失效、锁粒度过粗
       │
V3.0  自定义 SimpleRedisLock（SETNX + Lua 解锁）
  └─ 问题：锁超时可能自动释放，导致并发问题
       │
V4.0  Redisson 分布式锁（WatchDog 自动续期）
  └─ 问题：锁等待期间数据库压力仍然较大
       │
V5.0  Lua 原子化 + Redis Stream 异步下单 ✅
  └─ 最终方案：原子校验、异步解耦、高吞吐、数据一致
```

## 快速开始

### 环境要求

- JDK 8+
- Maven 3.6+
- MySQL 5.7+
- Redis（与配置中地址/端口/密码一致）

### 1. 初始化数据库

执行项目中的 SQL 脚本：

```
src/main/resources/db/hmdp.sql
```

### 2. 修改配置

编辑 `src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
    password: 你的密码
  redis:
    host: 你的 Redis 地址
    port: 6379
    password: 你的 Redis 密码
```

### 3. 启动项目

```bash
mvn spring-boot:run
```

或先打包再运行：

```bash
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

启动后访问端口：**8081**

### 4. 接口示例

| 接口                          | 方法 | 说明                 |
| ----------------------------- | ---- | -------------------- |
| `/user/code`                  | POST | 发送短信验证码       |
| `/user/login`                 | POST | 登录获取 Token       |
| `/shop/{id}`                  | GET  | 查询商户详情（缓存） |
| `/shop/of/type`               | GET  | 按类型+坐标查商户    |
| `/shop/of/name`               | GET  | 按名称搜索商户       |
| `/blog/hot`                   | GET  | 热门博客列表         |
| `/blog/like/{id}`             | PUT  | 点赞/取消点赞        |
| `/blog/of/follow`             | GET  | 关注 Feed 流         |
| `/voucher-order/seckill/{id}` | POST | 秒杀优惠券           |
| `/user/sign`                  | POST | 用户签到             |
| `/user/sign/count`            | GET  | 签到统计             |
| `/follow/{id}/{isFollow}`     | PUT  | 关注/取关            |
| `/follow/common/{id}`         | GET  | 共同关注列表         |
| `/upload/blog`                | POST | 图片上传             |

## 项目结构

```
src/main/java/com/hmdp/
├── config/
│   ├── MvcConfig.java              # MVC 拦截器注册
│   ├── MybatisConfig.java          # MyBatis-Plus 分页插件
│   ├── RedisConfig.java            # Redisson 客户端配置
│   └── WebExceptionAdvice.java     # 全局异常处理
├── controller/                     # 10 个 REST 控制器
├── service/                        # 服务接口 + impl 实现
├── mapper/                         # MyBatis Mapper
├── entity/                         # 实体类（与表映射）
├── dto/                            # 请求/响应 DTO
└── utils/
    ├── CacheClient.java            # 通用缓存工具（穿透/击穿封装）
    ├── SimpleRedisLock.java        # 自定义 Redis 分布式锁
    ├── RedisWorker.java            # 全局唯一 ID 生成器
    ├── RefreshTokenInterceptor.java # Token 刷新拦截器
    ├── LoginInterceptor.java       # 登录鉴权拦截器
    ├── UserHolder.java             # ThreadLocal 用户上下文
    ├── RedisData.java              # 逻辑过期封装对象
    └── RedisConstants.java         # Redis Key 常量

src/main/resources/
├── application.yaml                # 主配置
├── seckill.lua                     # 秒杀 Lua 脚本
├── unlock.lua                      # 分布式锁解锁 Lua 脚本
└── mapper/VoucherMapper.xml        # 自定义 SQL
```

## 注意事项

- `application.yaml` 中 Redis 和数据库密码为本地开发配置，部署时请替换为环境变量或 Spring Profile
- Redis Geo 搜索依赖商户坐标数据已预导入，可通过数据脚本初始化
- 秒杀接口依赖 Redis Stream，需 Redis 5.0+ 版本
- 图片上传目录 `IMAGE_UPLOAD_DIR` 在 `SystemConstants.java` 中配置
