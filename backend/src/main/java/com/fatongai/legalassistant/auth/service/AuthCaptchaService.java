package com.fatongai.legalassistant.auth.service;

import com.fatongai.legalassistant.auth.dto.CaptchaMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AuthCaptchaService {
    private static final Logger log = LoggerFactory.getLogger(AuthCaptchaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final String exchange;
    private final String routingKey;

    public AuthCaptchaService(RabbitTemplate rabbitTemplate,
                              StringRedisTemplate redisTemplate,
                              @Value("${app.auth.captcha.rabbit.exchange:legal.auth.captcha.exchange}") String exchange,
                              @Value("${app.auth.captcha.rabbit.routing-key:legal.auth.captcha}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void requestCode(String phone, String scene) {
        String cooldownKey = cooldownKey(phone);
        Boolean locked = redisTemplate.hasKey(cooldownKey);
        if (Boolean.TRUE.equals(locked)) {
            throw new IllegalArgumentException("验证码已发送，请1分钟后再试");
        }
        redisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofMinutes(1));
        rabbitTemplate.convertAndSend(exchange, routingKey, new CaptchaMessage(phone, scene, LocalDateTime.now()));
    }

    public void verifyCode(String phone, String code) {
        String cached = redisTemplate.opsForValue().get(codeKey(phone));
        if (!StringUtils.hasText(cached)) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (!cached.equals(code)) {
            throw new IllegalArgumentException("验证码不正确");
        }
        redisTemplate.delete(codeKey(phone));
    }

    @RabbitListener(queues = "${app.auth.captcha.rabbit.queue:legal.auth.captcha.queue}")
    public void handleCaptchaMessage(CaptchaMessage message) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey(message.phone()), code, Duration.ofMinutes(5));
        log.info("[auth-captcha] 手机号={} 场景={} 验证码={} 有效期=5分钟", message.phone(), message.scene(), code);
        System.out.printf("[法通AI验证码] 手机号=%s 场景=%s 验证码=%s 有效期=5分钟%n",
                message.phone(), message.scene(), code);
    }

    private String codeKey(String phone) {
        return "auth:captcha:code:" + phone;
    }

    private String cooldownKey(String phone) {
        return "auth:captcha:cooldown:" + phone;
    }
}
