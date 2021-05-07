package org.kie.samples.integration;

import static java.util.Collections.singletonMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.jupiter.api.DisplayName;
import org.kie.api.task.model.Status;
import org.kie.samples.integration.testcontainers.KieServerContainer;
import org.kie.server.api.marshalling.MarshallingFormat;
import org.kie.server.api.model.KieContainerResource;
import org.kie.server.api.model.ReleaseId;
import org.kie.server.api.model.instance.ProcessInstance;
import org.kie.server.api.model.instance.TaskSummary;
import org.kie.server.client.KieServicesClient;
import org.kie.server.client.KieServicesConfiguration;
import org.kie.server.client.KieServicesFactory;
import org.kie.server.client.ProcessServicesClient;
import org.kie.server.client.QueryServicesClient;
import org.kie.server.client.UserTaskServicesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.github.dockerjava.api.DockerClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Testcontainers(disabledWithoutDocker=true)
public class TxEJBTimerSystemTest {
    
    public static final String SELECT_PARTITION_NAME_FROM_JBOSS_EJB_TIMER = "select partition_name from jboss_ejb_timer";
    public static final String PREFIX_CLI_PATH = "src/test/resources/etc/jbpm-custom-";
    public static final String SELECT_COUNT_FROM_JBOSS_EJB_TIMER = "select count(*) from jboss_ejb_timer";
    public static final String ARTIFACT_ID = "tx-ejb-sample";
    public static final String GROUP_ID = "org.kie.server.testing";
    public static final String VERSION = "1.0.0";
    public static final String ALIAS = "-alias";
    
    public static final String DEFAULT_USER = "kieserver";
    public static final String DEFAULT_PASSWORD = "kieserver1!";

    public static String containerId = GROUP_ID+":"+ARTIFACT_ID+":"+VERSION;

    private static Logger logger = LoggerFactory.getLogger(TxEJBTimerSystemTest.class);
    
    private static Map<String, String> args = new HashMap<>();

    static {
        args.put("IMAGE_NAME", System.getProperty("org.kie.samples.image"));
        args.put("START_SCRIPT", System.getProperty("org.kie.samples.script"));
        args.put("SERVER", System.getProperty("org.kie.samples.server"));
        createCLIFile("node1");
    }

    @ClassRule
    public static Network network = Network.newNetwork();
    
    @ClassRule
    public static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(System.getProperty("org.kie.samples.image.postgresql","postgres:latest"))
                                        .withDatabaseName("rhpamdatabase")
                                        .withUsername("rhpamuser")
                                        .withPassword("rhpampassword")
                                        .withFileSystemBind("etc/postgresql", "/docker-entrypoint-initdb.d",
                                                            BindMode.READ_ONLY)
                                        //.withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("[PostgresContainer]"))
                                        .withCommand("-c max_prepared_transactions=10")
                                        .withNetwork(network)
                                        .withNetworkAliases("postgresql11");
    
    
    @ClassRule
    public static KieServerContainer kieServer = new KieServerContainer("node1", network, args);
    
    private static KieServicesClient ksClient;
    
    private static ProcessServicesClient processClient;
    private static UserTaskServicesClient taskClient;
    
    private static HikariDataSource ds;
    
    @BeforeClass
    public static void setup() {
        logger.info("KIE SERVER 1 started at port "+kieServer.getKiePort());
        logger.info("postgresql started at "+postgreSQLContainer.getJdbcUrl());
        
        ksClient = authenticate(kieServer.getKiePort(), DEFAULT_USER, DEFAULT_PASSWORD);
        
        processClient = ksClient.getServicesClient(ProcessServicesClient.class);
        taskClient = ksClient.getServicesClient(UserTaskServicesClient.class);
        
        ds = getDataSource();
    }
    
    @Before
    public void before() {
        createContainer(ksClient);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        DockerClient docker = DockerClientFactory.instance().client();
        docker.listImagesCmd().withLabelFilter("autodelete=true").exec().stream()
         .filter(c -> c.getId() != null)
         .forEach(c -> docker.removeImageCmd(c.getId()).withForce(true).exec());
    }
    
    @After
    public void after() throws Exception {
        ksClient.disposeContainer(containerId);
        
        assertEquals("No timer at the table after disposal",
                0, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
    }

    @Test
    @DisplayName("test executing a subprocess with an intermediate timer and a script that sends an exception")
    public void testTimerFailSubprocess() throws Exception {
        startProcessAndSignal("timer-fail-subprocess", 1);
    }
    
    @Test
    @DisplayName("test executing a subprocess with a boundary timer in a human task and a script that sends an exception")
    public void testBoundaryFailSubprocess() throws Exception {
        startProcessAndSignal("boundary-subprocess", 2);
    }
    
    @Test
    @DisplayName("test executing a subprocess with a gateway diverging to a human task with boundary timer and a script that sends an exception")
    public void testBoundaryGatewaySubprocess() throws Exception {
        startProcessAndSignal("boundary-gateway-subprocess", 1);
    }
    

    private void startProcessAndSignal(String processId, int expectedTimersAfterRollback) throws InterruptedException, SQLException {
        Long processInstanceId = processClient.startProcess(containerId, processId);
        
        assertTrue(processInstanceId>0);
        
        logger.info("Sleeping 1 s");
        Thread.sleep(1000);
        
        assertEquals("there should be just one timer at the table",
                      1, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
        
        logger.info("Sending signal");
        processClient.signal(containerId, "Signal", null);
        
        logger.info("Sleeping 5 s");
        Thread.sleep(5000);
        
        assertEquals("there should be "+expectedTimersAfterRollback+" timer at the table after the rollback",
                expectedTimersAfterRollback, performQuery(SELECT_COUNT_FROM_JBOSS_EJB_TIMER).getInt(1));
    }
    
    private static void createContainer(KieServicesClient client) {
        ReleaseId releaseId = new ReleaseId(GROUP_ID, ARTIFACT_ID, VERSION);
        KieContainerResource resource = new KieContainerResource(containerId, releaseId);
        resource.setContainerAlias(ARTIFACT_ID + ALIAS);
        client.createContainer(containerId, resource);
    }

    private static void createCLIFile(String nodeName) {
        Boolean noCluster = Boolean.getBoolean("org.kie.samples.ejbtimer.nocluster");
        //if different partitions are defined per nodeName, then there is no cluster for EJB timers
        String node = noCluster? nodeName : "node1";
        try {
             String content = FileUtils.readFileToString(new File(PREFIX_CLI_PATH+"template.cli"), "UTF-8");
             content = content.replaceAll("%partition_name%", "\\\"ejb_timer_"+node+"_part\\\"");
             File cliFile = new File(PREFIX_CLI_PATH+nodeName+".cli");
             FileUtils.writeStringToFile(cliFile, content, "UTF-8");
             cliFile.deleteOnExit();
          } catch (IOException e) {
             throw new RuntimeException("Generating file failed", e);
          }
    }

    private static KieServicesClient authenticate(int port, String user, String password) {
        String serverUrl = "http://localhost:" + port + "/kie-server/services/rest/server";
        KieServicesConfiguration configuration = KieServicesFactory.newRestConfiguration(serverUrl, user, password);
        
        configuration.setTimeout(60000);
        configuration.setMarshallingFormat(MarshallingFormat.JSON);
        return  KieServicesFactory.newKieServicesClient(configuration);
    }

    private static HikariDataSource getDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        hikariConfig.setUsername(postgreSQLContainer.getUsername());
        hikariConfig.setPassword(postgreSQLContainer.getPassword());
        hikariConfig.setDriverClassName(postgreSQLContainer.getDriverClassName());
        
        return new HikariDataSource(hikariConfig);
    }
    
    protected static ResultSet performQuery(String sql) throws SQLException {
        try (Connection conn = ds.getConnection(); PreparedStatement st = conn.prepareStatement(sql)) {
           ResultSet rs = st.executeQuery();
           rs.next();
           return rs;
        }
    }
    
}

