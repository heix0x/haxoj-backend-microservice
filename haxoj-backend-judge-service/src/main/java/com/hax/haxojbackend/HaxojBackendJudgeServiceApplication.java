package com.hax.haxojbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 判题服务启动类（项目启动入口）
 */
@SpringBootApplication
@EnableFeignClients
public class HaxojBackendJudgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HaxojBackendJudgeServiceApplication.class, args);
    }

}
