package com.example.tsbot;

import com.example.tsbot.config.AutoDjProperties;
import com.example.tsbot.config.Ts3Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({Ts3Properties.class, AutoDjProperties.class})
public class TsBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(TsBotApplication.class, args);
    }
}