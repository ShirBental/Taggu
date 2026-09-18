package com.taggu.app;

import com.taggu.app.config.TagguProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point. Local-first: nothing here reaches outside the machine it runs on.
 *
 * <p>The scan is listed explicitly rather than inherited from this package, because the repositories
 * live in the persistence module and the domain modules deliberately contain no Spring at all.
 */
@SpringBootApplication(scanBasePackages = {"com.taggu.app", "com.taggu.persistence"})
@EnableConfigurationProperties(TagguProperties.class)
public class TagguApplication {

    public static void main(String[] args) {
        SpringApplication.run(TagguApplication.class, args);
    }
}
