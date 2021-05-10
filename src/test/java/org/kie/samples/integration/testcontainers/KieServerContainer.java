package org.kie.samples.integration.testcontainers;

import java.io.File;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

public class KieServerContainer extends GenericContainer<KieServerContainer>{

    private static final String ALIAS = "kie-server";
    private static final int KIE_PORT = 8080;

    private static Logger logger = LoggerFactory.getLogger(KieServerContainer.class);

    public KieServerContainer(Network network, Map<String,String> args) {
      super( new ImageFromDockerfile()
           .withBuildArg("IMAGE_NAME", args.get("IMAGE_NAME"))
           .withFileFromFile("etc/jbpm-custom.cli", new File("src/test/resources/etc/jbpm-custom-node1.cli"))
           .withFileFromClasspath("etc/kjars", "etc/kjars")
           .withFileFromClasspath("Dockerfile", "etc/Dockerfile")
           .withFileFromClasspath("etc/drivers/postgresql-42.2.19.jar", "etc/drivers/postgresql-42.2.19.jar")
           .withFileFromClasspath("etc/jbpm-flow-7.52.1-SNAPSHOT.jar", "etc/jbpm-flow-7.52.1-SNAPSHOT.jar")
           .withFileFromClasspath("etc/jbpm-persistence-jpa-7.52.1-SNAPSHOT.jar", "etc/jbpm-persistence-jpa-7.52.1-SNAPSHOT.jar")
           .withFileFromClasspath("etc/jbpm-runtime-manager-7.52.1-SNAPSHOT.jar", "etc/jbpm-runtime-manager-7.52.1-SNAPSHOT.jar")
           .withFileFromClasspath("etc/jbpm-services-ejb-timer-7.52.1-SNAPSHOT.jar", "etc/jbpm-services-ejb-timer-7.52.1-SNAPSHOT.jar"));
    
      withEnv("START_SCRIPT", args.get("START_SCRIPT"));
      withEnv("JAVA_OPTS", "-Xms256m -Xmx2048m -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=512m -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 "+
                           "-Dorg.jbpm.ejb.timer.local.cache="+args.get("cache")+" -Dorg.jbpm.ejb.timer.tx="+args.get("timer-tx"));
      withNetwork(network);
      withNetworkAliases(ALIAS);
      withExposedPorts(KIE_PORT);
      withLogConsumer(new Slf4jLogConsumer(logger).withPrefix("KIE-LOG"));
      waitingFor(Wait.forLogMessage(".*WildFly.*started in.*", 1).withStartupTimeout(Duration.ofMinutes(5L)));
    }
    
    public Integer getKiePort() {
        return this.getMappedPort(KIE_PORT);
    }
}
