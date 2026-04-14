package com.demo.router;

import org.kie.api.io.Resource;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Constructs the {@link DMNRuntime} bean from every {@code .dmn} file
 * on the classpath under {@code classpath:/dmn/**}.
 *
 * <p>Equivalent to what the Kogito Spring Boot starter does internally
 * — we wire it here by hand because the 10.x Kogito starter artefacts
 * are not yet published to Maven Central. Swap this class out for
 * {@code @EnableKogito} once the upstream starter is back on Central.
 */
@Configuration
public class DmnRuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(DmnRuntimeConfig.class);

    @Bean
    public DMNRuntime dmnRuntime() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        org.springframework.core.io.Resource[] springResources = resolver.getResources("classpath*:dmn/*.dmn");
        if (springResources.length == 0) {
            log.warn("dmn.runtime.no-files-found pattern=classpath*:dmn/*.dmn");
        }
        List<Resource> kieResources = new ArrayList<>();
        for (org.springframework.core.io.Resource r : springResources) {
            String filename = r.getFilename() == null ? "model.dmn" : r.getFilename();
            log.info("dmn.runtime.loading file={}", filename);
            Resource kieRes = ResourceFactory.newReaderResource(
                    new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8));
            kieRes.setSourcePath(filename);
            kieResources.add(kieRes);
        }
        DMNRuntime runtime = DMNRuntimeBuilder.fromDefaults()
                .buildConfiguration()
                .fromResources(kieResources)
                .getOrElseThrow(e -> new IllegalStateException(
                        "Failed to build DMNRuntime: " + e.getMessage(), e));
        log.info("dmn.runtime.ready models={}", runtime.getModels().size());
        return runtime;
    }
}
