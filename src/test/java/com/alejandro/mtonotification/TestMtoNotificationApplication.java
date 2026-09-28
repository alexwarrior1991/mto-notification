package com.alejandro.mtonotification;

import org.springframework.boot.SpringApplication;

public class TestMtoNotificationApplication {

    public static void main(String[] args) {
        SpringApplication.from(MtoNotificationApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
