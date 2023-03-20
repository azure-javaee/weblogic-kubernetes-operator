// Copyright (c) 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.verrazzano.weblogic.kubernetes;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.Yaml;
import oracle.verrazzano.weblogic.ApplicationConfiguration;
import oracle.verrazzano.weblogic.ApplicationConfigurationSpec;
import oracle.verrazzano.weblogic.Component;
import oracle.verrazzano.weblogic.ComponentSpec;
import oracle.verrazzano.weblogic.Components;
import oracle.verrazzano.weblogic.Destination;
import oracle.verrazzano.weblogic.IngressRule;
import oracle.verrazzano.weblogic.IngressTrait;
import oracle.verrazzano.weblogic.IngressTraitSpec;
import oracle.verrazzano.weblogic.IngressTraits;
import oracle.verrazzano.weblogic.Path;
import oracle.verrazzano.weblogic.Workload;
import oracle.verrazzano.weblogic.WorkloadSpec;
import oracle.verrazzano.weblogic.kubernetes.annotations.VzIntegrationTest;
import oracle.weblogic.domain.DomainResource;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.RESULTS_ROOT;
import static oracle.weblogic.kubernetes.TestConstants.TEST_IMAGES_REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.APP_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WORK_DIR;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.createApplication;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.createComponent;
import static oracle.weblogic.kubernetes.utils.BuildApplication.buildApplication;
import static oracle.weblogic.kubernetes.utils.CommonMiiTestUtils.createDomainResource;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.FileUtils.copyFolder;
import static oracle.weblogic.kubernetes.utils.FileUtils.replaceStringInFile;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createImageAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createTestRepoSecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.imageRepoLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.getIstioHost;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.getLoadbalancerAddress;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.setLabelToNamespace;
import static oracle.weblogic.kubernetes.utils.VerrazzanoUtils.verifyVzApplicationAccess;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Cross domain transaction tests.
 */
@DisplayName("Verify cross domain transaction is successful in verrazzano")
@VzIntegrationTest
@Tag("v8o")
class ItVzCrossDomainTransaction {

  //private static String domainNamespace = null;
  //private final String domainUid = "domain1";
  private static LoggingFacade logger = null;

  private static String domain1Namespace = null;
  private static String domain2Namespace = null;
  private static String domain1Image = null;
  private static String domain2Image = null;
  private static String domainUid1 = "domain1";
  private static String domainUid2 = "domain2";
  private static int  domain1AdminServiceNodePort = -1;
  private static int  admin2ServiceNodePort = -1;
  private static String domain1AdminServerPodName = domainUid1 + "-admin-server";
  private final String domain1ManagedServerPrefix = domainUid1 + "-managed-server";
  private static String domain2AdminServerPodName = domainUid2 + "-admin-server";
  private static String domain1AdminSecretName = domainUid1 + "-weblogic-credentials";
  private static  String domain2AdminSecretName = domainUid2 + "-weblogic-credentials";
  private static String domain1EncryptionSecretName = domainUid1 + "-encryptionsecret";
  private static String domain2EncryptionSecretName = domainUid2 + "-encryptionsecret";
  private final String domain2ManagedServerPrefix = domainUid2 + "-managed-server";
  private final int replicaCount = 2;
  private static final String ORACLEDBURLPREFIX = "oracledb.";
  private static String ORACLEDBSUFFIX = null;
  static String dbUrl;
  static int dbNodePort;
  private static String domain1AdminExtSvcRouteHost = null;
  private static String domain2AdminExtSvcRouteHost = null;
  private static String adminExtSvcRouteHost = null;
  private static String hostAndPort = null;
  private static String dbPodIP = null;
  private static int dbPort = 1521;

  private static final String WDT_MODEL_FILE_DOMAIN1 = "model-crossdomaintransaction-domain1.yaml";
  private static final String WDT_MODEL_FILE_DOMAIN2 = "model-crossdomaintransaction-domain2.yaml";

  private static final String WDT_MODEL_DOMAIN1_PROPS = "model-crossdomaintransaction-domain1.properties";
  private static final String WDT_MODEL_DOMAIN2_PROPS = "model-crossdomaintransaction-domain2.properties";
  private static final String WDT_IMAGE_NAME1 = "domain1-cdxaction-wdt-image";
  private static final String WDT_IMAGE_NAME2 = "domain2-cdxaction-wdt-image";
  private static final String PROPS_TEMP_DIR = RESULTS_ROOT + "/crossdomaintransactiontemp";
  private static final String WDT_MODEL_FILE_JMS = "model-cdt-jms.yaml";
  private static final String WDT_MODEL_FILE_JDBC = "model-cdt-jdbc.yaml";
  private static final String WDT_MODEL_FILE_JMS2 = "model2-cdt-jms.yaml";


  /**
   * Label domain namespace.
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(2) List<String> namespaces) throws Exception {
    logger = getLogger();
    logger.info("Getting unique namespace for Domain");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    domain1Namespace = namespaces.get(0);
    setLabelToNamespace(domain1Namespace);
    domain2Namespace = namespaces.get(1);
    setLabelToNamespace(domain2Namespace);

    updatePropertyFile();
    buildApplicationsAndDomainImages();
  }

  /**
   * Create a WebLogic domain VerrazzanoWebLogicWorkload component in verrazzano.
   */
  @Test
  @DisplayName("Create model in image domain and verify services and pods are created and ready in verrazzano.")
  void testCreateVzMiiDomain1() {

    // admin/managed server name here should match with model yaml in MII_BASIC_WDT_MODEL_FILE
    /*final String adminServerPodName = domainUid1 + "-admin-server";
    final String managedServerPrefix = domainUid1 + "-managed-server";
    final int replicaCount = 2;*/

    // Create the repo secret to pull the image
    // this secret is used only for non-kind cluster
    createTestRepoSecret(domain1Namespace);

    // create secret for admin credentials
    /*logger.info("Create secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    createSecretWithUsernamePassword(adminSecretName, domain1Namespace,
            ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create encryption secret
    logger.info("Create encryption secret");
    String encryptionSecretName = "encryptionsecret";
    createSecretWithUsernamePassword(encryptionSecretName, domain1Namespace,
            "weblogicenc", "weblogicenc");*/

    // create cluster object
    String clusterName = "cluster-1";

    DomainResource domain = createDomainResource(domainUid1, domain1Namespace,
        domain1Image,
        domain1AdminSecretName, new String[]{TEST_IMAGES_REPO_SECRET_NAME},
        domain1EncryptionSecretName, replicaCount, Arrays.asList(clusterName));

    Component component = new Component()
        .apiVersion("core.oam.dev/v1alpha2")
        .kind("Component")
        .metadata(new V1ObjectMeta()
            .name(domainUid1)
            .namespace(domain1Namespace))
        .spec(new ComponentSpec()
            .workLoad(new Workload()
                .apiVersion("oam.verrazzano.io/v1alpha1")
                .kind("VerrazzanoWebLogicWorkload")
                .spec(new WorkloadSpec()
                    .template(domain))));

    Map<String, String> keyValueMap = new HashMap<>();
    keyValueMap.put("version", "v1.0.0");
    keyValueMap.put("description", "My vz wls application");

    ApplicationConfiguration application = new ApplicationConfiguration()
        .apiVersion("core.oam.dev/v1alpha2")
        .kind("ApplicationConfiguration")
        .metadata(new V1ObjectMeta()
            .name("myvzdomain")
            .namespace(domain1Namespace)
            .annotations(keyValueMap))
        .spec(new ApplicationConfigurationSpec()
            .components(Arrays.asList(new Components()
                .componentName(domainUid1)
                .traits(Arrays.asList(new IngressTraits()
                    .trait(new IngressTrait()
                        .apiVersion("oam.verrazzano.io/v1alpha1")
                        .kind("IngressTrait")
                        .metadata(new V1ObjectMeta()
                            .name("mydomain-ingress")
                            .namespace(domain1Namespace))
                        .spec(new IngressTraitSpec()
                            .ingressRules(Arrays.asList(
                                new IngressRule()
                                    .paths(Arrays.asList(new Path()
                                        .path("/console")
                                        .pathType("Prefix")))
                                    .destination(new Destination()
                                        .host(domain1AdminServerPodName)
                                        .port(7001)),
                                new IngressRule()
                                    .paths(Arrays.asList(new Path()
                                        .path("/sample-war")
                                        .pathType("Prefix")))
                                    .destination(new Destination()
                                        .host(domainUid1 + "-cluster-" + clusterName)
                                        .port(8001)))))))))));

    logger.info(Yaml.dump(component));
    logger.info(Yaml.dump(application));

    logger.info("Deploying components");
    assertDoesNotThrow(() -> createComponent(component));
    logger.info("Deploying application");
    assertDoesNotThrow(() -> createApplication(application));

    // check admin server pod is ready
    logger.info("Wait for admin server pod {0} to be ready in namespace {1}",
        domain1AdminServerPodName, domain1Namespace);
    checkPodReadyAndServiceExists(domain1AdminServerPodName, domainUid1, domain1Namespace);
    // check managed server pods are ready
    for (int i = 1; i <= replicaCount; i++) {
      logger.info("Wait for managed server pod {0} to be ready in namespace {1}",
          domain1ManagedServerPrefix + i, domain1Namespace);
      checkPodReadyAndServiceExists(domain1ManagedServerPrefix + i, domainUid1, domain1Namespace);
    }

    // get istio gateway host and loadbalancer address
    String host = getIstioHost(domain1Namespace);
    String address = getLoadbalancerAddress();

    // verify WebLogic console page is accessible through istio/loadbalancer
    String message = "Oracle WebLogic Server Administration Console";
    String consoleUrl = "https://" + host + "/console/login/LoginForm.jsp --resolve " + host + ":443:" + address;
    assertTrue(verifyVzApplicationAccess(consoleUrl, message), "Failed to get WebLogic administration console");

    // verify sample running in cluster is accessible through istio/loadbalancer
    message = "Hello World, you have reached server managed-server";
    String appUrl = "https://" + host + "/sample-war/index.jsp --resolve " + host + ":443:" + address;
    assertTrue(verifyVzApplicationAccess(appUrl, message), "Failed to get access to sample application");

  }

  private static void updatePropertyFile() {
    //create a temporary directory to copy and update the properties file
    java.nio.file.Path target = Paths.get(PROPS_TEMP_DIR);
    java.nio.file.Path source1 = Paths.get(MODEL_DIR, WDT_MODEL_DOMAIN1_PROPS);
    java.nio.file.Path source2 = Paths.get(MODEL_DIR, WDT_MODEL_DOMAIN2_PROPS);
    logger.info("Copy the properties file to the above area so that we can add namespace property");
    assertDoesNotThrow(() -> {
      Files.createDirectories(target);
      Files.copy(source1, target.resolve(source1.getFileName()), StandardCopyOption.REPLACE_EXISTING);
      Files.copy(source2, target.resolve(source2.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    });

    assertDoesNotThrow(
        () -> addToPropertyFile(WDT_MODEL_DOMAIN1_PROPS, domain1Namespace),
        String.format("Failed to update %s with namespace %s", WDT_MODEL_DOMAIN1_PROPS, domain1Namespace));
    assertDoesNotThrow(
        () -> addToPropertyFile(WDT_MODEL_DOMAIN2_PROPS, domain2Namespace),
        String.format("Failed to update %s with namespace %s", WDT_MODEL_DOMAIN2_PROPS, domain2Namespace));

  }

  private static void addToPropertyFile(String propFileName, String domainNamespace) throws IOException {
    FileInputStream in = new FileInputStream(PROPS_TEMP_DIR + "/" + propFileName);
    Properties props = new Properties();
    props.load(in);
    in.close();

    FileOutputStream out = new FileOutputStream(PROPS_TEMP_DIR + "/" + propFileName);
    props.setProperty("NAMESPACE", domainNamespace);
    //props.setProperty("K8S_NODEPORT_HOST", dbPodIP);
    //props.setProperty("DBPORT", Integer.toString(dbPort));
    props.store(out, null);
    out.close();
  }

  private static void buildApplicationsAndDomainImages() {

    //build application archive

    java.nio.file.Path targetDir = Paths.get(WORK_DIR,
        ItVzCrossDomainTransaction.class.getName() + "/txforward");
    java.nio.file.Path distDir = buildApplication(Paths.get(APP_DIR, "txforward"), null, null,
        "build", domain1Namespace, targetDir);
    logger.info("distDir is {0}", distDir.toString());
    assertTrue(Paths.get(distDir.toString(),
        "txforward.ear").toFile().exists(),
        "Application archive is not available");
    String appSource = distDir.toString() + "/txforward.ear";
    logger.info("Application is in {0}", appSource);

    //build application archive
    targetDir = Paths.get(WORK_DIR,
        ItVzCrossDomainTransaction.class.getName() + "/cdtservlet");
    distDir = buildApplication(Paths.get(APP_DIR, "cdtservlet"), null, null,
        "build", domain1Namespace, targetDir);
    logger.info("distDir is {0}", distDir.toString());
    assertTrue(Paths.get(distDir.toString(),
        "cdttxservlet.war").toFile().exists(),
        "Application archive is not available");
    String appSource1 = distDir.toString() + "/cdttxservlet.war";
    logger.info("Application is in {0}", appSource1);

    //build application archive for JMS Send/Receive
    targetDir = Paths.get(WORK_DIR,
        ItVzCrossDomainTransaction.class.getName() + "/jmsservlet");
    distDir = buildApplication(Paths.get(APP_DIR, "jmsservlet"), null, null,
        "build", domain1Namespace, targetDir);
    logger.info("distDir is {0}", distDir.toString());
    assertTrue(Paths.get(distDir.toString(),
        "jmsservlet.war").toFile().exists(),
        "Application archive is not available");
    String appSource2 = distDir.toString() + "/jmsservlet.war";
    logger.info("Application is in {0}", appSource2);

    java.nio.file.Path mdbSrcDir = Paths.get(APP_DIR, "mdbtopic");
    java.nio.file.Path mdbDestDir = Paths.get(PROPS_TEMP_DIR, "mdbtopic");

    assertDoesNotThrow(() -> copyFolder(
        mdbSrcDir.toString(), mdbDestDir.toString()),
        "Could not copy mdbtopic application directory");

    java.nio.file.Path template = Paths.get(PROPS_TEMP_DIR,
        "mdbtopic/src/application/MdbTopic.java");

    // Add the domain2 namespace decorated URL to the providerURL of MDB
    // so that it can communicate with remote destination on domain2
    assertDoesNotThrow(() -> replaceStringInFile(
        template.toString(), "domain2-namespace", domain2Namespace),
        "Could not modify the domain2Namespace in MDB Template file");

    //build application archive for MDB
    targetDir = Paths.get(WORK_DIR,
        ItVzCrossDomainTransaction.class.getName() + "/mdbtopic");
    distDir = buildApplication(Paths.get(PROPS_TEMP_DIR, "mdbtopic"), null, null,
        "build", domain1Namespace, targetDir);
    logger.info("distDir is {0}", distDir.toString());
    assertTrue(Paths.get(distDir.toString(),
        "mdbtopic.jar").toFile().exists(),
        "Application archive is not available");
    String appSource3 = distDir.toString() + "/mdbtopic.jar";
    logger.info("Application is in {0}", appSource3);

    // create admin credential secret for domain1
    logger.info("Create admin credential secret for domain1");
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        domain1AdminSecretName, domain1Namespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT),
        String.format("createSecret %s failed for %s", domain1AdminSecretName, domainUid1));
    //create encryption secret for domain1
    logger.info("Create encryption secret for domain1 ");
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(domain1EncryptionSecretName, domain1Namespace,
            "weblogicenc", "weblogicenc"),
        String.format("create encryption secret  %s failed for %s", domain1EncryptionSecretName, domainUid1));

    // create admin credential secret for domain2
    logger.info("Create admin credential secret for domain2");
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(
        domain2AdminSecretName, domain2Namespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT),
        String.format("createSecret %s failed for %s", domain2AdminSecretName, domainUid2));
    //create encryption secret for domain2
    logger.info("Create encryption secret for domain2 ");
    assertDoesNotThrow(() -> createSecretWithUsernamePassword(domain2EncryptionSecretName, domain2Namespace,
            "weblogicenc", "weblogicenc"),
        String.format("create encryption secret  %s failed for %s", domain2EncryptionSecretName, domainUid2));

    // build the model file list for domain1
    final List<String> modelListDomain1 = Arrays.asList(
        MODEL_DIR + "/" + WDT_MODEL_FILE_DOMAIN1,
        MODEL_DIR + "/" + WDT_MODEL_FILE_JMS);

    final List<String> appSrcDirList1 = Arrays.asList(appSource, appSource1, appSource2, appSource3);

    logger.info("Creating image with model file and verify");
    domain1Image = createImageAndVerify(
        WDT_IMAGE_NAME1, modelListDomain1, appSrcDirList1, WDT_MODEL_DOMAIN1_PROPS, PROPS_TEMP_DIR, domainUid1);
    logger.info("Created domain1: {0} image", domain1Image);

    // repo login and push image to registry if necessary
    imageRepoLoginAndPushImageToRegistry(domain1Image);

    // build the model file list for domain2
    final List<String> modelListDomain2 = Arrays.asList(
        MODEL_DIR + "/" + WDT_MODEL_FILE_DOMAIN2,
        MODEL_DIR + "/" + WDT_MODEL_FILE_JMS2,
        MODEL_DIR + "/" + WDT_MODEL_FILE_JDBC);

    final List<String> appSrcDirList2 = Collections.singletonList(appSource);

    logger.info("Creating image with model file and verify");
    domain2Image = createImageAndVerify(
        WDT_IMAGE_NAME2, modelListDomain2, appSrcDirList2, WDT_MODEL_DOMAIN2_PROPS, PROPS_TEMP_DIR, domainUid2);
    logger.info("Created domain2: {0} image", domain2Image);

    // repo login and push image to registry if necessary
    imageRepoLoginAndPushImageToRegistry(domain2Image);

  }
}