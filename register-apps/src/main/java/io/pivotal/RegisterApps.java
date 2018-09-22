package io.pivotal;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.services.GetServiceInstanceRequest;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cloud.dataflow.core.ApplicationType;
import org.springframework.cloud.dataflow.rest.client.AppRegistryOperations;
import org.springframework.cloud.dataflow.rest.client.DataFlowTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;

@Component
public class RegisterApps implements CommandLineRunner {

    public static final String DATAFLOW_SERVICE_INSTANCE_NAME = "dataflow-server";
    public static final String STREAMS_BULK_URI = "http://bit.ly/Celsius-SR3-stream-applications-rabbit-maven";
    public static final String TASKS_BULK_URI = "http://bit.ly/Clark-GA-task-applications-maven";
    public static final String NLP_PROCESSOR_URI = "https://s3.amazonaws.com/maven-shgupta/nlp-processor-0.0.1-SNAPSHOT.jar";
    public static final String REDIS_SINK_URI = "https://s3.amazonaws.com/maven-shgupta/redis-sink-0.0.1-SNAPSHOT.jar";

    private final ConnectionContext _connectionContext;
    private final TokenProvider _tokenProvider;
    private final CloudFoundryClient _cloudFoundryClient;
    private final CloudFoundryOperations _cloudFoundryOperations;

    private String accessToken = "";

    @Autowired
    public RegisterApps(ConnectionContext connectionContext, TokenProvider tokenProvider,
                        CloudFoundryClient cloudFoundryClient, CloudFoundryOperations cloudFoundryOperations) {
        this._connectionContext = connectionContext;
        this._tokenProvider = tokenProvider;
        this._cloudFoundryClient = cloudFoundryClient;
        this._cloudFoundryOperations = cloudFoundryOperations;
    }

    @Override
    public void run(String... args) throws Exception {


        ServiceInstance dataflowInstance = _cloudFoundryOperations.services()
                                                                  .getInstance(GetServiceInstanceRequest.builder()
                                                                                                        .name(DATAFLOW_SERVICE_INSTANCE_NAME)
                                                                                                        .build())
                                                                  .block();


        if(dataflowInstance != null) {
            URI serverUri;
            String dashboardUrl = dataflowInstance.getDashboardUrl();
            System.out.println("Data Flow Server dashboard url: " + dashboardUrl);
            try {
                serverUri = new URI(dashboardUrl.substring(0, dashboardUrl.lastIndexOf("/")));

                try {
                    registerApps(serverUri);
                } catch(IOException e) {
                    throw new UncheckedIOException(e);
                }
            } catch(URISyntaxException e) {
                System.out.println("Could not parse URI: " + dashboardUrl);
            }
        }
    }

    class AuthorizationInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {

            HttpHeaders headers = request.getHeaders();
            headers.add(HttpHeaders.AUTHORIZATION, accessToken);
            return execution.execute(request, body);
        }
    }


    private void registerApps(URI serverUri) throws URISyntaxException, IOException {

        // Set up OAuth Flow
        accessToken = _tokenProvider.getToken(_connectionContext)
                                    .block();

        RestTemplate template = DataFlowTemplate.prepareRestTemplate(null);
        template.setInterceptors(Collections.singletonList(new AuthorizationInterceptor()));


        DataFlowTemplate dataFlowTemplate = new DataFlowTemplate(serverUri, template);

        AppRegistryOperations appRegistryOperations = dataFlowTemplate.appRegistryOperations();
        appRegistryOperations.importFromResource(STREAMS_BULK_URI, true);
        appRegistryOperations.importFromResource(TASKS_BULK_URI, true);
        appRegistryOperations.register("nlp", ApplicationType.processor, NLP_PROCESSOR_URI, null, true);
        appRegistryOperations.register("redis", ApplicationType.sink, REDIS_SINK_URI, null, true);

        //        Resource resource = new ClassPathResource("appStarters.properties");
        //        Properties properties = PropertiesLoaderUtils.loadProperties(resource);
        //        appRegistryOperations.registerAll(properties, true);

        System.out.println(appRegistryOperations.list()
                                                .getContent()
                                                .size() + " starter apps registered");
    }
}
