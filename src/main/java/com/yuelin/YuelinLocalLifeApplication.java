package com.yuelin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@MapperScan("com.yuelin.mapper")
@SpringBootApplication
@EnableScheduling
public class YuelinLocalLifeApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuelinLocalLifeApplication.class, args);
    }

}
