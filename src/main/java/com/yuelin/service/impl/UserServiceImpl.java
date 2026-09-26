package com.yuelin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yuelin.dto.LoginFormDTO;
import com.yuelin.dto.Result;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.User;
import com.yuelin.mapper.UserMapper;
import com.yuelin.service.IUserService;
import com.yuelin.service.verification.VerificationCodeSender;
import com.yuelin.utils.RegexUtils;
import com.yuelin.utils.SystemConstants;
import com.yuelin.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.yuelin.utils.RedisConstants.*;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    private static final long ONE_MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long FIVE_MINUTES_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final long TWENTY_MINUTES_MILLIS = TimeUnit.MINUTES.toMillis(20);
    private static final long MAX_SENDS_PER_FIVE_MINUTES = 5L;
    private static final long MAX_SENDS_PER_TWENTY_MINUTES = 8L;
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;
    private static final DefaultRedisScript<Long> STORE_CODE_SCRIPT;
    private static final DefaultRedisScript<Long> CONSUME_CODE_SCRIPT;
    private static final DefaultRedisScript<Long> CREATE_SESSION_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("verification_rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
        STORE_CODE_SCRIPT = new DefaultRedisScript<>();
        STORE_CODE_SCRIPT.setLocation(new ClassPathResource("verification_code_store.lua"));
        STORE_CODE_SCRIPT.setResultType(Long.class);
        CONSUME_CODE_SCRIPT = new DefaultRedisScript<>();
        CONSUME_CODE_SCRIPT.setLocation(new ClassPathResource("verification_code_consume.lua"));
        CONSUME_CODE_SCRIPT.setResultType(Long.class);
        CREATE_SESSION_SCRIPT = new DefaultRedisScript<>();
        CREATE_SESSION_SCRIPT.setLocation(new ClassPathResource("login_session_create.lua"));
        CREATE_SESSION_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VerificationCodeSender verificationCodeSender;

    // 发送验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }

        String historyKey = SENDCODE_SENDTIME_KEY + phone;
        long now = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        Long rateLimitResult = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(historyKey),
                Long.toString(now),
                requestId,
                Long.toString(ONE_MINUTE_MILLIS),
                Long.toString(FIVE_MINUTES_MILLIS),
                Long.toString(TWENTY_MINUTES_MILLIS),
                Long.toString(MAX_SENDS_PER_FIVE_MINUTES),
                Long.toString(MAX_SENDS_PER_TWENTY_MINUTES));
        if (rateLimitResult == null) {
            return Result.fail("验证码服务暂时不可用，请稍后重试");
        }
        if (rateLimitResult == 1L) {
            return Result.fail("距离上次发送时间不足1分钟，请1分钟后重试");
        }
        if (rateLimitResult == 2L) {
            return Result.fail("5分钟内验证码发送次数过多，请稍后重试");
        }
        if (rateLimitResult == 3L) {
            return Result.fail("20分钟内验证码发送次数过多，请稍后重试");
        }

        String code = RandomUtil.randomNumbers(6);
        String codeKey = LOGIN_CODE_KEY + phone;
        String attemptsKey = LOGIN_CODE_ATTEMPT_KEY + phone;
        try {
            Long stored = stringRedisTemplate.execute(
                    STORE_CODE_SCRIPT,
                    Arrays.asList(codeKey, attemptsKey),
                    code,
                    Long.toString(TimeUnit.MINUTES.toSeconds(LOGIN_CODE_TTL)));
            if (stored == null || stored != 1L) {
                throw new IllegalStateException("验证码服务暂时不可用");
            }
            verificationCodeSender.send(phone, code);
        } catch (RuntimeException sendFailure) {
            try {
                stringRedisTemplate.delete(Arrays.asList(codeKey, attemptsKey));
            } catch (RuntimeException cleanupFailure) {
                sendFailure.addSuppressed(cleanupFailure);
            }
            try {
                stringRedisTemplate.opsForZSet().remove(historyKey, requestId);
            } catch (RuntimeException cleanupFailure) {
                sendFailure.addSuppressed(cleanupFailure);
            }
            throw sendFailure;
        }
        return Result.ok();
    }

    //登录注册
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if (loginForm == null) {
            return Result.fail("登录参数不能为空");
        }
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }
        if (RegexUtils.isCodeInvalid(code)) {
            return Result.fail("验证码应为6位数字");
        }
        // 原子校验并消费验证码，避免同一验证码被并发重复登录。
        Long codeResult = stringRedisTemplate.execute(
                CONSUME_CODE_SCRIPT,
                Arrays.asList(LOGIN_CODE_KEY + phone, LOGIN_CODE_ATTEMPT_KEY + phone),
                code,
                Integer.toString(LOGIN_CODE_MAX_ATTEMPTS));
        if (codeResult != null && codeResult == -2L) {
            return Result.fail("验证码尝试次数过多，请重新获取");
        }
        if (codeResult == null || codeResult != 1L) {
            return Result.fail("无效的验证码");
        }
        //如果上述都没有问题的话，就从数据库中查询该用户的信息

        //select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //判断用户是否存在
        if (user==null)
        {
            user = createuser(phone);
        }
        //保存用户信息到Redis中
        String token = UUID.randomUUID().toString();

        //7.2 将用户会话和 TTL 通过同一条 Lua 命令原子写入，避免产生永久 token。
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String tokenKey = LOGIN_USER_KEY + token;
        Long sessionCreated = stringRedisTemplate.execute(
                CREATE_SESSION_SCRIPT,
                Collections.singletonList(tokenKey),
                String.valueOf(userDTO.getId()),
                userDTO.getNickName() == null ? "" : userDTO.getNickName(),
                userDTO.getIcon() == null ? "" : userDTO.getIcon(),
                Long.toString(TimeUnit.MINUTES.toSeconds(LOGIN_USER_TTL)));
        if (sessionCreated == null || sessionCreated != 1L) {
            throw new IllegalStateException("登录会话创建失败");
        }

        //8. 返回token
        return Result.ok(token);
    }


    @Override
    public Result logout(String token) {
        if (token == null || token.trim().isEmpty()) {
            UserHolder.removeUser();
            return Result.ok();
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        return Result.ok();
    }

    private User createuser(String phone) {
        //创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        // 数据库手机号唯一索引处理并发注册；冲突时读取并复用已创建用户。
        try {
            if (!save(user)) {
                throw new IllegalStateException("用户注册失败");
            }
            return user;
        } catch (DuplicateKeyException concurrentRegistration) {
            User existing = query().eq("phone", phone).one();
            if (existing == null) {
                throw concurrentRegistration;
            }
            return existing;
        }
    }

    @Override
    public Result sign() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();
        //5. 写入Redis  BITSET key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        //3. 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4. 获取今天是当月第几天(1~31)
        int dayOfMonth = now.getDayOfMonth();


        //5. 获取截止至今日的签到记录  BITFIELD key GET uDay 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        //6. 循环遍历
        int count = 0;
        Long num = result.get(0);
        if (num == null) {
            return Result.ok(0);
        }
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else
                count++;
            //数字右移，抛弃最后一位
            num = num>>>1;
        }
        return Result.ok(count);
    }
}
