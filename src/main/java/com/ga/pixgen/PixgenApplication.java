package com.ga.pixgen;

import com.ga.pixgen.config.GenerationModelsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GenerationModelsProperties.class)
public class PixgenApplication {

	public static void main(String[] args) {
		SpringApplication.run(PixgenApplication.class, args);
	}

}
