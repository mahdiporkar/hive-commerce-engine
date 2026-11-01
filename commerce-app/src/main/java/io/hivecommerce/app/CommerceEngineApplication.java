package io.hivecommerce.app;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackages = "io.hivecommerce")
public class CommerceEngineApplication {
  public static void main(String[] args) {
    SpringApplication.run(CommerceEngineApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
