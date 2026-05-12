package com.fatongai.legalassistant.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fatongai.legalassistant.auth.dto.AuthResponse;
import com.fatongai.legalassistant.auth.dto.AuthUserView;
import com.fatongai.legalassistant.auth.dto.LoginRequest;
import com.fatongai.legalassistant.auth.dto.RegisterRequest;
import com.fatongai.legalassistant.auth.dto.ResetPasswordRequest;
import com.fatongai.legalassistant.auth.dto.SmsLoginRequest;
import com.fatongai.legalassistant.auth.entity.AuthUser;
import com.fatongai.legalassistant.auth.mapper.AuthUserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthUserMapper userMapper;
    private final AuthCaptchaService captchaService;
    private final StringRedisTemplate redisTemplate;

    public AuthService(AuthUserMapper userMapper,
                       AuthCaptchaService captchaService,
                       StringRedisTemplate redisTemplate) {
        this.userMapper = userMapper;
        this.captchaService = captchaService;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if ("lawyer".equals(req.role()) && !StringUtils.hasText(req.licenseNo())) {
            throw new IllegalArgumentException("律师注册请填写执业证号");
        }
        captchaService.verifyCode(req.phone(), req.code());
        AuthUser existed = findByPhoneAndRole(req.phone(), req.role());
        if (existed != null) {
            throw new IllegalArgumentException("该手机号已注册当前身份，请直接登录");
        }

        String salt = newSalt();
        AuthUser user = new AuthUser();
        user.setRole(req.role());
        user.setPhone(req.phone());
        user.setPasswordSalt(salt);
        user.setPasswordHash(hashPassword(req.password(), salt));
        user.setLicenseNo(StringUtils.hasText(req.licenseNo()) ? req.licenseNo().trim() : null);
        user.setDisplayName("lawyer".equals(req.role()) ? "律师用户" : "普通用户");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return createSession(user, Boolean.TRUE.equals(req.remember()));
    }

    public AuthResponse login(LoginRequest req) {
        AuthUser user = requireActiveUser(req.phone(), req.role());
        if (!hashPassword(req.password(), user.getPasswordSalt()).equals(user.getPasswordHash())) {
            throw new IllegalArgumentException("账号、密码或身份不匹配");
        }
        return createSession(user, Boolean.TRUE.equals(req.remember()));
    }

    public AuthResponse smsLogin(SmsLoginRequest req) {
        captchaService.verifyCode(req.phone(), req.code());
        AuthUser user = requireActiveUser(req.phone(), req.role());
        return createSession(user, Boolean.TRUE.equals(req.remember()));
    }

    public void resetPassword(ResetPasswordRequest req) {
        captchaService.verifyCode(req.phone(), req.code());
        AuthUser user = requireActiveUser(req.phone(), req.role());
        String salt = newSalt();
        user.setPasswordSalt(salt);
        user.setPasswordHash(hashPassword(req.newPassword(), salt));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    public AuthUserView me(String token) {
        Long userId = userIdByToken(token);
        AuthUser user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new IllegalArgumentException("登录状态已失效");
        }
        return toView(user);
    }

    public boolean isTokenValid(String token) {
        if (!StringUtils.hasText(token)) return false;
        return Boolean.TRUE.equals(redisTemplate.hasKey(tokenKey(token)));
    }

    public Long userIdByToken(String token) {
        String raw = redisTemplate.opsForValue().get(tokenKey(token));
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录");
        }
        return Long.parseLong(raw);
    }

    private AuthResponse createSession(AuthUser user, boolean remember) {
        String token = UUID.randomUUID().toString().replace("-", "") + "." + user.getId();
        Duration ttl = remember ? Duration.ofDays(7) : Duration.ofHours(12);
        redisTemplate.opsForValue().set(tokenKey(token), String.valueOf(user.getId()), ttl);
        return new AuthResponse(token, toView(user));
    }

    private AuthUser requireActiveUser(String phone, String role) {
        AuthUser user = findByPhoneAndRole(phone, role);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号不存在或身份不匹配");
        }
        return user;
    }

    private AuthUser findByPhoneAndRole(String phone, String role) {
        return userMapper.selectOne(new LambdaQueryWrapper<AuthUser>()
                .eq(AuthUser::getPhone, phone)
                .eq(AuthUser::getRole, role)
                .last("LIMIT 1"));
    }

    private AuthUserView toView(AuthUser user) {
        return new AuthUserView(user.getId(), user.getRole(), user.getPhone(), user.getDisplayName(), user.getLicenseNo());
    }

    private String newSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("密码处理失败", e);
        }
    }

    private String tokenKey(String token) {
        return "auth:session:" + token;
    }
}
