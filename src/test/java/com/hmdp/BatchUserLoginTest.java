package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@SpringBootTest
public class BatchUserLoginTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 定义输出文件路径，请根据需要修改（Windows示例路径）
    private static final String OUTPUT_FILE_PATH = "D:/heimadianping/token.txt";

    @Test
    public void loginAllUsersToRedis() {
        // 1. 查询所有用户
        List<User> users = userMapper.selectList(null);
        System.out.println("共 " + users.size() + " 个用户待处理");

        // 使用 try-with-resources 自动管理文件流，确保写入后自动关闭
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE_PATH))) {

            for (User user : users) {
                // 2. 转换 User -> UserDTO
                UserDTO userDTO = new UserDTO();
                BeanUtil.copyProperties(user, userDTO);

                // 3. 将 UserDTO 转为 Map（值转为 String，忽略 null）
                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

                // 4. 生成 token
                String token = UUID.randomUUID().toString();
                String key = LOGIN_USER_KEY + token;

                // 5. 存入 Redis Hash
                stringRedisTemplate.opsForHash().putAll(key, userMap);

                // 6. 设置过期时间（基准 TTL + 随机 0~5 分钟）
                long ttl = LOGIN_USER_TTL + RandomUtil.randomLong(0, 5);
                stringRedisTemplate.expire(key, ttl, TimeUnit.MINUTES);

                // 7. 将 Token 写入文件 (每个 token 一行)
                writer.write(token);
                writer.newLine(); // 换行，方便 JMeter 逐行读取

                // 8. 控制台打印（可选，用于观察进度）
                System.out.printf("用户 %s (手机号: %s) 登录成功，token: %s%n", user.getNickName(), user.getPhone(), token);
            }

            System.out.println("--------------------------------------------------");
            System.out.println("全部用户登录信息已存入 Redis");
            System.out.println("所有 Token 已保存至: " + OUTPUT_FILE_PATH);
            System.out.println("--------------------------------------------------");

        } catch (IOException e) {
            System.err.println("文件写入失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}