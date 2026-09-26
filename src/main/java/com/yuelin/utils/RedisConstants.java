package com.yuelin.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final String LOGIN_CODE_ATTEMPT_KEY = "login:code:attempts:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final int LOGIN_CODE_MAX_ATTEMPTS = 5;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOP_TYPE_KEY = "cache:shoptype:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_RESERVATION_PENDING_KEY = "seckill:reservation:pending";
    public static final String SECKILL_RESERVATION_TIMEOUT_KEY = "seckill:reservation:timeout";
    public static final String SECKILL_RESERVATION_RETRY_KEY = "seckill:reservation:retry";
    public static final String SECKILL_RESERVATION_PENDING_COUNT_KEY = "seckill:reservation:pending-count";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String SENDCODE_SENDTIME_KEY ="sms:sendtime:";


}
