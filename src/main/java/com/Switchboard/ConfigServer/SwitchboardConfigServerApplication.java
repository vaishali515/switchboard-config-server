package com.Switchboard.ConfigServer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@SpringBootApplication
@EnableConfigServer
public class SwitchboardConfigServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SwitchboardConfigServerApplication.class, args);
	}

}
