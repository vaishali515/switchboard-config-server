package com.Switchboard.ConfigServer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.cloud.config.server.environment.SearchPathLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for AWS Parameter Store integration
 * Uses BeanPostProcessor to wrap the existing Git EnvironmentRepository
 * and enrich responses with AWS Parameter Store secrets
 */
@Configuration
public class AwsParameterStoreConfiguration implements org.springframework.beans.factory.config.BeanPostProcessor {

    @Value("${spring.cloud.aws.credentials.access-key}")
    private String accessKey;

    @Value("${spring.cloud.aws.credentials.secret-key}")
    private String secretKey;

    @Value("${spring.cloud.aws.region.static}")
    private String region;

    @Value("${aws.parameterstore.prefix:/switchboard}")
    private String prefix;

    private SsmClient ssmClient;

    /**
     * Creates an SSM client for Parameter Store access
     */
    @Bean
    public SsmClient ssmClient() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        
        this.ssmClient = SsmClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
        
        System.out.println("AWS Parameter Store integration initialized with prefix: " + prefix);
        return this.ssmClient;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // Only wrap the CompositeEnvironmentRepository (the top-level repository)
        // This prevents duplicate AWS Parameter Store calls
        if (beanName.equals("defaultEnvironmentRepository") && 
            bean instanceof EnvironmentRepository &&
            !(bean instanceof AwsEnrichedEnvironmentRepository)) {
            System.out.println("Wrapping " + beanName + " to add AWS Parameter Store support");
            return wrapWithAwsParameterStore((EnvironmentRepository) bean);
        }
        return bean;
    }

    private Object wrapWithAwsParameterStore(EnvironmentRepository delegate) {
        // Use the new wrapper class that implements both interfaces
        return new AwsEnrichedEnvironmentRepository(delegate);
    }
    
    /**
     * Wrapper class that implements both EnvironmentRepository and SearchPathLocator
     */
    private class AwsEnrichedEnvironmentRepository implements EnvironmentRepository, SearchPathLocator {
        private final EnvironmentRepository delegate;
        private final SearchPathLocator searchPathDelegate;
        
        public AwsEnrichedEnvironmentRepository(EnvironmentRepository delegate) {
            this.delegate = delegate;
            this.searchPathDelegate = (delegate instanceof SearchPathLocator) ? (SearchPathLocator) delegate : null;
        }
        
        @Override
        public Environment findOne(String application, String profile, String label) {
            return findOne(application, profile, label, false);
        }

        @Override
        public Environment findOne(String application, String profile, String label, boolean includeOrigin) {
            // First, get configuration from the delegate (Git/Composite)
            Environment environment = delegate.findOne(application, profile, label, includeOrigin);
            
            System.out.println("Enriching environment for " + application + " with AWS Parameter Store");
            
            // Then, enrich with AWS Parameter Store
            enrichWithAwsParameters(environment, application);
            
            return environment;
        }
        
        @Override
        public Locations getLocations(String application, String profile, String label) {
            // Delegate to the original implementation if it supports SearchPathLocator
            if (searchPathDelegate != null) {
                return searchPathDelegate.getLocations(application, profile, label);
            }
            // Return empty locations if not supported
            return new Locations(application, profile, label, null, new String[0]);
        }
    }

    /**
     * Enrich environment with AWS Parameter Store parameters
     */
    private void enrichWithAwsParameters(Environment environment, String application) {
        try {
            if (ssmClient == null) {
                ssmClient = ssmClient();
            }
            
            String path = prefix + "/" + application;
            Map<String, String> properties = fetchParameters(ssmClient, path);
            
            if (!properties.isEmpty()) {
                PropertySource propertySource = new PropertySource(
                        "aws-parameter-store:" + path,
                        properties
                );
                environment.add(propertySource);
                System.out.println("Added " + properties.size() + " parameters from AWS Parameter Store: " + path);
            } else {
                System.out.println("No parameters found at: " + path);
            }
        } catch (Exception e) {
            System.err.println("Error fetching from AWS Parameter Store for " + application + ": " + e.getMessage());
            // Don't print full stack trace, just log the error
        }
    }

    /**
     * Fetch parameters from AWS Parameter Store
     */
    private static Map<String, String> fetchParameters(SsmClient ssmClient, String path) {
        Map<String, String> properties = new HashMap<>();
        String nextToken = null;
        
        do {
            GetParametersByPathRequest.Builder requestBuilder = GetParametersByPathRequest.builder()
                    .path(path)
                    .recursive(true)
                    .withDecryption(true)
                    .maxResults(10);
            
            if (nextToken != null) {
                requestBuilder.nextToken(nextToken);
            }
            
            GetParametersByPathResponse response = ssmClient.getParametersByPath(requestBuilder.build());
            List<Parameter> parameters = response.parameters();
            
            for (Parameter parameter : parameters) {
                String key = extractKeyFromPath(parameter.name(), path);
                properties.put(key, parameter.value());
                System.out.println("Fetched parameter: " + key);
            }
            
            nextToken = response.nextToken();
        } while (nextToken != null);
        
        return properties;
    }

    /**
     * Convert parameter path to property key
     */
    private static String extractKeyFromPath(String fullPath, String basePath) {
        String key = fullPath.substring(basePath.length());
        if (key.startsWith("/")) {
            key = key.substring(1);
        }
        return key.replace("/", ".");
    }
}
