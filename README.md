Transactional EJB Timers (persisted with postgresql) in jBPM
========================================================

## Building

For building this project locally, you firstly need to have the following tools installed locally:
- git client
- Java 1.8
- Maven
- docker (because of testcontainers makes use of it).

Once you cloned the repository locally all you need to do is execute the following Maven build (for cluster scenarios):

```
mvn clean install
```

and the following for no-cluster scenarios:

```
mvn clean install -Dorg.kie.samples.ejbtimer.nocluster=true
```

This project is using only `kie-server-showcase` image but it is prepared for adding other images at any other profile (current default profile is *kie-server*).

Happy confirmation testing!! :tada::tada::tada:
