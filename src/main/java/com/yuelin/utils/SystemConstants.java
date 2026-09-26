package com.yuelin.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = System.getenv().getOrDefault("YUELIN_IMAGE_DIR", "./data/images");
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
