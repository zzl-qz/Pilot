package com.pilot.server.browser;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pilot.core.browser.BrowserHandoffBroker;

/**
 * 注册浏览器接管等待协议（{@link BrowserHandoffBroker}），用于在工具与实时控制面板之间同步挂起与恢复。
 */
@Configuration
public class BrowserHandoffConfiguration {

    @Bean
    public BrowserHandoffBroker browserHandoffBroker() {
        return new BrowserHandoffBroker();
    }
}
