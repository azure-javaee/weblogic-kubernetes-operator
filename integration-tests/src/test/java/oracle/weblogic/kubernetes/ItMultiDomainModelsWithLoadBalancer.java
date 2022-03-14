// Copyright (c) 2022, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1SecretReference;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.AdminService;
import oracle.weblogic.domain.Channel;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.NginxParams;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.DomainUtils;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.file.Paths.get;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_PASSWORD_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_USERNAME_DEFAULT;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_APP_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OCIR_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.OKD;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_RELEASE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.WEBLOGIC_SLIM;
import static oracle.weblogic.kubernetes.TestConstants.WLS_DOMAIN_TYPE;
import static oracle.weblogic.kubernetes.actions.ActionConstants.ARCHIVE_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.MODEL_DIR;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WLDF_CLUSTER_ROLE_BINDING_NAME;
import static oracle.weblogic.kubernetes.actions.ActionConstants.WLDF_CLUSTER_ROLE_NAME;
import static oracle.weblogic.kubernetes.actions.TestActions.buildAppArchive;
import static oracle.weblogic.kubernetes.actions.TestActions.defaultAppParams;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteClusterRole;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteClusterRoleBinding;
import static oracle.weblogic.kubernetes.actions.TestActions.execCommand;
import static oracle.weblogic.kubernetes.actions.TestActions.getContainerRestartCount;
import static oracle.weblogic.kubernetes.actions.TestActions.getDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.getOperatorPodName;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.getServicePort;
import static oracle.weblogic.kubernetes.actions.TestActions.now;
import static oracle.weblogic.kubernetes.actions.TestActions.uninstallNginx;
import static oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes.copyFileToPod;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.adminNodePortAccessible;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.clusterRoleBindingExists;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.clusterRoleExists;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.callWebAppAndCheckForServerNameInResponse;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.callWebAppAndWaitTillReady;
import static oracle.weblogic.kubernetes.utils.ApplicationUtils.verifyAdminConsoleAccessible;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.getNextFreePort;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.scaleAndVerifyCluster;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.startPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.stopPortForwardProcess;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.testUntil;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.withLongRetryPolicy;
import static oracle.weblogic.kubernetes.utils.DeployUtil.deployUsingWlst;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createAndVerifyDomainInImageUsingWdt;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.DomainUtils.createDomainOnPvUsingWdt;
import static oracle.weblogic.kubernetes.utils.FileUtils.doesFileExistInPod;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createMiiImageAndVerify;
import static oracle.weblogic.kubernetes.utils.ImageUtils.createOcirRepoSecret;
import static oracle.weblogic.kubernetes.utils.ImageUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.K8sEvents.DOMAIN_CHANGED;
import static oracle.weblogic.kubernetes.utils.K8sEvents.DOMAIN_COMPLETED;
import static oracle.weblogic.kubernetes.utils.K8sEvents.POD_STARTED;
import static oracle.weblogic.kubernetes.utils.K8sEvents.POD_TERMINATED;
import static oracle.weblogic.kubernetes.utils.K8sEvents.checkDomainEvent;
import static oracle.weblogic.kubernetes.utils.K8sEvents.checkPodEventLoggedOnce;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.createIngressForDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.LoadBalancerUtils.installAndVerifyNginx;
import static oracle.weblogic.kubernetes.utils.LoggingUtil.checkPodLogContainsString;
import static oracle.weblogic.kubernetes.utils.OKDUtils.createRouteForOKD;
import static oracle.weblogic.kubernetes.utils.OKDUtils.getRouteHost;
import static oracle.weblogic.kubernetes.utils.OKDUtils.setTlsTerminationForRoute;
import static oracle.weblogic.kubernetes.utils.OperatorUtils.installAndVerifyOperator;
import static oracle.weblogic.kubernetes.utils.PatchDomainUtils.patchDomainResource;
import static oracle.weblogic.kubernetes.utils.PodUtils.checkPodReady;
import static oracle.weblogic.kubernetes.utils.PodUtils.getExternalServicePodName;
import static oracle.weblogic.kubernetes.utils.PodUtils.setPodAntiAffinity;
import static oracle.weblogic.kubernetes.utils.SecretUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The test class creates WebLogic domains with three models.
 *  domain-on-pv ( using WDT )
 *  domain-in-image ( using WDT )
 *  model-in-image
 * Verify the basic lifecycle operations of the WebLogic server pods by scaling the domain and
 * triggering rolling ( in case of mii domain )
 * Also verify the sample application can be accessed via NGINX ingress controller.
 */
@DisplayName("Verify scaling the clusters in the domain with different domain types, "
        + "rolling restart behavior in a multi-cluster MII domain and "
        + "the sample application can be accessed via NGINX ingress controller")
@IntegrationTest
class ItMultiDomainModelsWithLoadBalancer {

  // domain constants
  private static final int NUMBER_OF_CLUSTERS_MIIDOMAIN = 2;
  private static final String CLUSTER_NAME_PREFIX = "cluster-";
  private static final String clusterName = "cluster-1";
  private static final int MANAGED_SERVER_PORT = 8001;
  private static final int ADMIN_SERVER_PORT = 7001;
  private static final int ADMIN_SERVER_SECURE_PORT = 7002;
  private static final int replicaCount = 2;
  private static final String SAMPLE_APP_CONTEXT_ROOT = "sample-war";
  private static final String WLDF_OPENSESSION_APP = "opensessionapp";
  private static final String WLDF_OPENSESSION_APP_CONTEXT_ROOT = "opensession";
  private static final String wlSecretName = "weblogic-credentials";
  private static final String DATA_HOME_OVERRIDE = "/u01/mydata";
  private static final String miiImageName = "mii-image";
  private static final String wdtModelFileForMiiDomain = "model-multiclusterdomain-sampleapp-wls.yaml";
  private static final String miiDomainUid = "miidomain";
  private static final String dimDomainUid = "domaininimage";
  private static final String wdtModelFileForDomainInImage = "wdt-singlecluster-multiapps-usingprop-wls.yaml";

  private static String opNamespace = null;
  private static String opServiceAccount = null;
  private static NginxParams nginxHelmParams = null;
  private static int nodeportshttp = 0;
  private static int externalRestHttpsPort = 0;
  private static List<Domain> domains = new ArrayList<>();
  private static LoggingFacade logger = null;
  private static Domain miiDomain = null;
  private static Domain domainInImage = null;
  private static Domain domainOnPV = null;
  private static String miiDomainNamespace = null;
  private static String miiDomainNegativeNamespace = null;
  private static String miiDomainNegativeNamespacePortforward = null;
  private static String miiImage = null;
  private static String encryptionSecretName = "encryptionsecret";
  private static Map<String, Quantity> resourceRequest = new HashMap<>();
  private static Map<String, Quantity> resourceLimit = new HashMap<>();
  private static String operExtSvcRouteHost = null;
  private String curlCmd = null;

  /**
   * Install operator and NGINX.
   * Create three different type of domains: model in image, domain in PV and domain in image.
   * Create ingress for each domain.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(7) List<String> namespaces) {
    logger = getLogger();

    // get a unique operator namespace
    logger.info("Get a unique namespace for operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    // get a unique NGINX namespace
    logger.info("Get a unique namespace for NGINX");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    String nginxNamespace = namespaces.get(1);

    // get unique namespaces for three different type of domains
    logger.info("Getting unique namespaces for three different type of domains");
    assertNotNull(namespaces.get(2));
    miiDomainNamespace = namespaces.get(2);
    assertNotNull(namespaces.get(3));
    String domainOnPVNamespace = namespaces.get(3);
    assertNotNull(namespaces.get(4));
    String domainInImageNamespace = namespaces.get(4);
    assertNotNull(namespaces.get(5));
    miiDomainNegativeNamespace = namespaces.get(5);
    miiDomainNegativeNamespacePortforward = namespaces.get(6);

    // set the service account name for the operator
    opServiceAccount = opNamespace + "-sa";

    // create mii image
    miiImage = createAndPushMiiImage();

    // install and verify operator with REST API
    installAndVerifyOperator(opNamespace, opServiceAccount, true, 0,
        miiDomainNamespace, domainOnPVNamespace, domainInImageNamespace,
        miiDomainNegativeNamespace, miiDomainNegativeNamespacePortforward);

    externalRestHttpsPort = getServiceNodePort(opNamespace, "external-weblogic-operator-svc");
    // This test uses the operator restAPI to scale the domain. To do this in OKD cluster,
    // we need to expose the external service as route and set tls termination to  passthrough
    logger.info("Create a route for the operator external service - only for OKD");
    operExtSvcRouteHost = createRouteForOKD("external-weblogic-operator-svc", opNamespace);
    // Patch the route just created to set tls termination to passthrough
    setTlsTerminationForRoute("external-weblogic-operator-svc", opNamespace);

    if (!OKD) {
      // install and verify NGINX
      nginxHelmParams = installAndVerifyNginx(nginxNamespace, 0, 0);
      String nginxServiceName = nginxHelmParams.getHelmParams().getReleaseName() + "-ingress-nginx-controller";
      logger.info("NGINX service name: {0}", nginxServiceName);
      nodeportshttp = getServiceNodePort(nginxNamespace, nginxServiceName, "http");
      logger.info("NGINX http node port: {0}", nodeportshttp);
    }

    // set resource request and limit
    resourceRequest.put("cpu", new Quantity("250m"));
    resourceRequest.put("memory", new Quantity("768Mi"));
    resourceLimit.put("cpu", new Quantity("2"));
    resourceLimit.put("memory", new Quantity("2Gi"));

    // create model in image domain with multiple clusters
    miiDomain = createMiiDomainWithMultiClusters(miiDomainNamespace);

    // create domain in image
    List<String> appSrcDirList = new ArrayList<>();
    appSrcDirList.add(MII_BASIC_APP_NAME);
    appSrcDirList.add(WLDF_OPENSESSION_APP);
    domainInImage = createAndVerifyDomainInImageUsingWdt(dimDomainUid, domainInImageNamespace,
        wdtModelFileForDomainInImage, appSrcDirList, wlSecretName, clusterName, replicaCount);

    // create domain on pv
    domainOnPV = createDomainOnPvUsingWdt(domainOnPVNamespace);

    domains.add(miiDomain);
    domains.add(domainInImage);
    domains.add(domainOnPV);

    // create ingress for each domain
    for (Domain domain: domains) {
      assertDomainNotNull(domain);

      String domainUid = domain.getSpec().getDomainUid();
      String domainNamespace = domain.getMetadata().getNamespace();

      //create route for external admin service
      createRouteForOKD(domainUid + "-admin-server-ext", domainNamespace);

      // create ingress using host based routing
      Map<String, Integer> clusterNameMsPortMap = new HashMap<>();
      int numClusters = domain.getSpec().getClusters().size();
      for (int i = 1; i <= numClusters; i++) {
        clusterNameMsPortMap.put(CLUSTER_NAME_PREFIX + i, MANAGED_SERVER_PORT);
        createRouteForOKD(domainUid + "-cluster-cluster-" + i, domainNamespace);
      }
      if (!OKD) {
        logger.info("Creating ingress for domain {0} in namespace {1}", domainUid, domainNamespace);
        createIngressForDomainAndVerify(domainUid, domainNamespace, nodeportshttp, clusterNameMsPortMap,
            true, nginxHelmParams.getIngressClassName(), true, ADMIN_SERVER_PORT);
      }
    }
  }

  /**
   * Negative test case for creating a model-in-image domain without encryption secret created.
   * The admin server service/pod will not be created.
   * Verify the error message should be logged in the operator log.
   */
  @Test
  @DisplayName("verify the operator log has expected error msg when encryption secret not created for a mii domain")
  void testOperatorLogSevereMsg() {
    createMiiDomainNegative("miidomainnegative", miiDomainNegativeNamespace);
    String operatorPodName = assertDoesNotThrow(() -> getOperatorPodName(OPERATOR_RELEASE_NAME, opNamespace));
    checkPodLogContainsString(opNamespace, operatorPodName,
        "Domain miidomainnegative is not valid: RuntimeEncryption secret '" + encryptionSecretName
        + "' not found in namespace '" + miiDomainNegativeNamespace + "'");
  }

  /**
   * Scale the cluster by patching domain resource for three different
   * type of domains i.e. domain-on-pv, domain-in-image and model-in-image
   *
   * @param domain oracle.weblogic.domain.Domain object
   */
  @ParameterizedTest
  @DisplayName("scale cluster by patching domain resource with three different type of domains")
  @MethodSource("domainProvider")
  void testScaleClustersByPatchingDomainResource(Domain domain) {
    assertDomainNotNull(domain);

    // get the domain properties
    String domainUid = domain.getSpec().getDomainUid();
    String domainNamespace = domain.getMetadata().getNamespace();
    int numClusters = domain.getSpec().getClusters().size();

    for (int i = 1; i <= numClusters; i++) {
      String clusterName = CLUSTER_NAME_PREFIX + i;
      String managedServerPodNamePrefix = generateMsPodNamePrefix(numClusters, domainUid, clusterName);

      int numberOfServers;
      // scale cluster-1 to 1 server and cluster-2 to 3 servers
      if (i == 1) {
        numberOfServers = 1;
      } else {
        numberOfServers = 3;
      }

      logger.info("Scaling cluster {0} of domain {1} in namespace {2} to {3} servers.",
          clusterName, domainUid, domainNamespace, numberOfServers);
      curlCmd = generateCurlCmd(domainUid, domainNamespace, clusterName, SAMPLE_APP_CONTEXT_ROOT);
      List<String> managedServersBeforeScale = listManagedServersBeforeScale(numClusters, clusterName, replicaCount);
      scaleAndVerifyCluster(clusterName, domainUid, domainNamespace, managedServerPodNamePrefix,
          replicaCount, numberOfServers, curlCmd, managedServersBeforeScale);

      // then scale cluster back to 2 servers
      logger.info("Scaling cluster {0} of domain {1} in namespace {2} from {3} servers to {4} servers.",
          clusterName, domainUid, domainNamespace, numberOfServers, replicaCount);
      managedServersBeforeScale = listManagedServersBeforeScale(numClusters, clusterName, numberOfServers);
      scaleAndVerifyCluster(clusterName, domainUid, domainNamespace, managedServerPodNamePrefix,
          numberOfServers, replicaCount, curlCmd, managedServersBeforeScale);
    }

    final String hostName = "localhost";
    String forwardedPortNo = startPortForwardProcess(hostName, domainNamespace, domainUid, ADMIN_SERVER_PORT);
    verifyAdminConsoleAccessible(domainNamespace, hostName, forwardedPortNo, false);

    forwardedPortNo = startPortForwardProcess(hostName, domainNamespace, domainUid, ADMIN_SERVER_SECURE_PORT);
    verifyAdminConsoleAccessible(domainNamespace, hostName, forwardedPortNo, true);

    stopPortForwardProcess(domainNamespace);

  }

  /**
   * Scale cluster using REST API for three different type of domains.
   * i.e. domain-on-pv, domain-in-image and model-in-image
   *
   * @param domain oracle.weblogic.domain.Domain object
   */
  @ParameterizedTest
  @DisplayName("scale cluster using REST API for three different type of domains")
  @MethodSource("domainProvider")
  void testScaleClustersWithRestApi(Domain domain) {
    assertDomainNotNull(domain);

    // get domain properties
    String domainUid = domain.getSpec().getDomainUid();
    String domainNamespace = domain.getMetadata().getNamespace();
    int numClusters = domain.getSpec().getClusters().size();
    String managedServerPodNamePrefix = generateMsPodNamePrefix(numClusters, domainUid, clusterName);
    int numberOfServers = 3;

    logger.info("Scaling cluster {0} of domain {1} in namespace {2} from {3} servers to {4} servers.",
        clusterName, domainUid, domainNamespace, replicaCount, numberOfServers);
    curlCmd = generateCurlCmd(domainUid, domainNamespace, clusterName, SAMPLE_APP_CONTEXT_ROOT);
    List<String> managedServersBeforeScale = listManagedServersBeforeScale(numClusters, clusterName, replicaCount);
    scaleAndVerifyCluster(clusterName, domainUid, domainNamespace, managedServerPodNamePrefix,
        replicaCount, numberOfServers, true, externalRestHttpsPort, opNamespace, opServiceAccount,
        false, "", "", 0, "", "", curlCmd, managedServersBeforeScale);

    // then scale cluster back to 2 servers
    logger.info("Scaling cluster {0} of domain {1} in namespace {2} from {3} servers to {4} servers.",
        clusterName, domainUid, domainNamespace, numberOfServers, replicaCount);
    managedServersBeforeScale = listManagedServersBeforeScale(numClusters, clusterName, numberOfServers);
    scaleAndVerifyCluster(clusterName, domainUid, domainNamespace, managedServerPodNamePrefix,
        numberOfServers, replicaCount, true, externalRestHttpsPort, opNamespace, opServiceAccount,
        false, "", "", 0, "", "", curlCmd, managedServersBeforeScale);
  }

  /**
   * Scale cluster using WLDF policy for three different type of domains.
   * i.e. domain-on-pv, domain-in-image and model-in-image
   *
   * @param domain oracle.weblogic.domain.Domain object
   */
  @ParameterizedTest
  @DisplayName("scale cluster using WLDF policy for three different type of domains")
  @MethodSource("domainProvider")
  void testScaleClustersWithWLDF(Domain domain) {
    assertDomainNotNull(domain);

    // get domain properties
    String domainUid = domain.getSpec().getDomainUid();
    String domainNamespace = domain.getMetadata().getNamespace();
    String domainHome = domain.getSpec().getDomainHome();
    int numClusters = domain.getSpec().getClusters().size();
    String managedServerPodNamePrefix = generateMsPodNamePrefix(numClusters, domainUid, clusterName);

    curlCmd = generateCurlCmd(domainUid, domainNamespace, clusterName, SAMPLE_APP_CONTEXT_ROOT);
    logger.info("BR: curlCmd = {0}", curlCmd);

    // scale up the cluster by 1 server
    logger.info("Scaling cluster {0} of domain {1} in namespace {2} from {3} servers to {4} servers.",
        clusterName, domainUid, domainNamespace, replicaCount, replicaCount + 1);
    List<String> managedServersBeforeScale = listManagedServersBeforeScale(numClusters, clusterName, replicaCount);
    String curlCmdForWLDFScript =
        generateCurlCmd(domainUid, domainNamespace, clusterName, WLDF_OPENSESSION_APP_CONTEXT_ROOT);
    logger.info("BR: curlCmdForWLDFScript = {0}", curlCmdForWLDFScript);

    scaleAndVerifyCluster(clusterName, domainUid, domainNamespace, managedServerPodNamePrefix,
        replicaCount, replicaCount + 1, false, 0, opNamespace, opServiceAccount,
        true, domainHome, "scaleUp", 1,
        WLDF_OPENSESSION_APP, curlCmdForWLDFScript, curlCmd, managedServersBeforeScale);

    // scale down the cluster by 1 server
    logger.info("Scaling cluster {0} of domain {1} in namespace {2} from {3} servers to {4} servers.",
        clusterName, domainUid, domainNamespace, replicaCount + 1, replicaCount);
    managedServersBeforeScale = listManagedServersBeforeScale(numClusters, clusterName, replicaCount + 1);

    scaleAndVerifyCluster(clusterName, domainUid, domainNamespace, managedServerPodNamePrefix,
        replicaCount + 1, replicaCount, false, 0, opNamespace, opServiceAccount,
        true, domainHome, "scaleDown", 1,
        WLDF_OPENSESSION_APP, curlCmdForWLDFScript, curlCmd, managedServersBeforeScale);
  }

  /**
   * Verify admin console login using admin node port.
   * Skip the test for slim images due to unavailability of console application
   * @param domain oracle.weblogic.domain.Domain object
   */
  @ParameterizedTest
  @DisplayName("Test admin console login using admin node port")
  @MethodSource("domainProvider")
  void testAdminConsoleLoginUsingAdminNodePort(Domain domain) {

    assumeFalse(WEBLOGIC_SLIM, "Skipping the Console Test for slim image");

    assertDomainNotNull(domain);
    String domainUid = domain.getSpec().getDomainUid();
    String domainNamespace = domain.getMetadata().getNamespace();
    String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;
    logger.info("Getting node port for default channel");
    int serviceNodePort = assertDoesNotThrow(() -> getServiceNodePort(
        domainNamespace, getExternalServicePodName(adminServerPodName), "default"),
        "Getting admin server node port failed");

    // In OKD cluster, we need to get the routeHost for the external admin service
    String routeHost = getRouteHost(domainNamespace, getExternalServicePodName(adminServerPodName));

    logger.info("Validating WebLogic admin server access by login to console");
    testUntil(
        assertDoesNotThrow(() -> {
          return adminNodePortAccessible(serviceNodePort, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT, routeHost);
        }, "Access to admin server node port failed"),
        logger,
        "Console login validation");
  }

  /**
   * Verify admin console login using ingress controller.
   * Skip the test for slim images due to unavailability of console application
   * @param domain oracle.weblogic.domain.Domain object
   */
  @ParameterizedTest
  @DisplayName("Test admin console login using ingress controller")
  @MethodSource("domainProvider")
  @DisabledIfEnvironmentVariable(named = "OKD", matches = "true")
  void testAdminConsoleLoginUsingIngressController(Domain domain) {

    assumeFalse(WEBLOGIC_SLIM, "Skipping the Console Test for slim image");

    assertDomainNotNull(domain);
    String domainUid = domain.getSpec().getDomainUid();
    String domainNamespace = domain.getMetadata().getNamespace();

    String curlCmd = "curl --silent --show-error --noproxy '*' -H 'host: "
        + domainUid + "." + domainNamespace + ".adminserver.test"
        + "' http://" + K8S_NODEPORT_HOST + ":" + nodeportshttp
        + "/console/login/LoginForm.jsp --write-out %{http_code} -o /dev/null";

    logger.info("Executing curl command {0}", curlCmd);
    assertTrue(callWebAppAndWaitTillReady(curlCmd, 60));
    logger.info("WebLogic console on domain1 is accessible");
  }

  /**
   * Verify liveness probe by killing managed server process 3 times to kick pod container auto-restart.
   */
  @Test
  @DisplayName("Test liveness probe of pod")
  void testLivenessProbe() {
    Domain domain = miiDomain;
    assertDomainNotNull(domain);
    String domainUid = domain.getSpec().getDomainUid();
    String domainNamespace = domain.getMetadata().getNamespace();
    int numClusters = domain.getSpec().getClusters().size();
    String serverName;
    if (numClusters > 1) {
      serverName = domainUid + "-" + clusterName + "-" + MANAGED_SERVER_NAME_BASE + "1";
    } else {
      serverName = domainUid + "-" + MANAGED_SERVER_NAME_BASE + "1";
    }

    // create file to kill server process
    File killServerScript = assertDoesNotThrow(() -> createScriptToKillServer(),
        "Failed to create script to kill server");
    logger.info("File/script created to kill server {0}", killServerScript);

    checkPodReady(serverName, domainUid, domainNamespace);

    // copy script to pod
    String destLocation = "/u01/killserver.sh";
    assertDoesNotThrow(() -> copyFileToPod(domainNamespace, serverName, "weblogic-server",
        killServerScript.toPath(), Paths.get(destLocation)),
        String.format("Failed to copy file %s to pod %s in namespace %s",
            killServerScript, serverName, domainNamespace));
    logger.info("File copied to Pod {0} in namespace {1}", serverName, domainNamespace);

    // get the restart count of the container in pod before liveness probe restarts
    final int beforeRestartCount =
        assertDoesNotThrow(() -> getContainerRestartCount(domainNamespace, null, serverName, null),
            String.format("Failed to get the restart count of the container from pod {0} in namespace {1}",
                serverName, domainNamespace));
    logger.info("Restart count before liveness probe {0}", beforeRestartCount);

    // change file permissions
    ExecResult execResult = assertDoesNotThrow(() -> execCommand(domainNamespace, serverName, null,
        true, "/bin/sh", "-c", "chmod +x " + destLocation),
        String.format("Failed to change permissions for file %s in pod %s", destLocation, serverName));
    assertTrue(execResult.exitValue() == 0,
        String.format("Failed to change file %s permissions, stderr %s stdout %s", destLocation,
            execResult.stderr(), execResult.stdout()));
    logger.info("File permissions changed inside pod");

    /* First, kill the managed server process in the container three times to cause the node manager to
     * mark the server 'failed not restartable'. This in turn is detected by the liveness probe, which
     * initiates a container restart.
     */
    for (int i = 0; i < 3; i++) {
      execResult = assertDoesNotThrow(() -> execCommand(domainNamespace, serverName, null,
          true, "/bin/sh", "-c", destLocation + " " + serverName),
          String.format("Failed to execute script %s in pod %s namespace %s", destLocation,
              serverName, domainNamespace));
      logger.info("Command executed to kill server inside pod, exit value {0}, stdout {1}, stderr {2}",
          execResult.exitValue(), execResult.stdout(), execResult.stderr());
      assertTrue(execResult.exitValue() == 0,
          String.format("Failed to execute kill server inside pod, stderr %s stdout %s", destLocation,
              execResult.stderr(), execResult.stdout()));
      logger.info("Command executed to kill server inside pod, exit value {0}, stdout {1}, stderr {2}",
          execResult.exitValue(), execResult.stdout(), execResult.stderr());

      try {
        Thread.sleep(2 * 1000);
      } catch (InterruptedException ie) {
        // ignore
      }
    }

    // check pod is ready
    checkPodReady(serverName, domainUid, domainNamespace);

    // get the restart count of the container in pod after liveness probe restarts
    int afterRestartCount = assertDoesNotThrow(() ->
            getContainerRestartCount(domainNamespace, null, serverName, null),
        String.format("Failed to get the restart count of the container from pod {0} in namespace {1}",
            serverName, domainNamespace));
    assertTrue(afterRestartCount - beforeRestartCount == 1,
        String.format("Liveness probe did not start the container in pod %s in namespace %s",
            serverName, domainNamespace));

    //access application in managed servers through NGINX load balancer
    logger.info("Accessing the sample app through NGINX load balancer");
    String curlCmd = generateCurlCmd(domainUid, domainNamespace, clusterName, SAMPLE_APP_CONTEXT_ROOT);
    List<String> managedServers = listManagedServersBeforeScale(numClusters, clusterName, replicaCount);
    assertThat(callWebAppAndCheckForServerNameInResponse(curlCmd, managedServers, 20))
        .as("Verify NGINX can access the test web app from all managed servers in the domain")
        .withFailMessage("NGINX can not access the test web app from one or more of the managed servers")
        .isTrue();
  }

  /**
   * Verify dataHome override in a domain with domain in image type.
   * In this domain, set dataHome to /u01/mydata in domain custom resource
   * The domain contains JMS and File Store configuration
   * File store directory is set to /u01/customFileStore in the model file which should be overridden by dataHome
   * File store and JMS server are targeted to the WebLogic cluster cluster-1
   * see resource/wdt-models/wdt-singlecluster-multiapps-usingprop-wls.yaml
   */
  @Test
  @DisplayName("Test dataHome override in a domain with domain in image type")
  void testDataHomeOverrideDomainInImage() {

    assertDomainNotNull(domainInImage);
    String domainUid = domainInImage.getSpec().getDomainUid();
    String domainNamespace = domainInImage.getMetadata().getNamespace();

    // check in admin server pod, there is no data file for JMS server created
    String dataFileToCheck = DATA_HOME_OVERRIDE + "/" + domainUid + "/FILESTORE-0000000.DAT";
    String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;
    assertFalse(assertDoesNotThrow(
        () -> doesFileExistInPod(domainNamespace, adminServerPodName, dataFileToCheck),
        String.format("exception thrown when checking file %s exists in pod %s in namespace %s",
            dataFileToCheck, adminServerPodName, domainNamespace)),
        String.format("%s exists in pod %s in namespace %s, expects not exist",
            dataFileToCheck, adminServerPodName, domainNamespace));

    // check in admin server pod, the default admin server data file moved to DATA_HOME_OVERRIDE
    String defaultAdminDataFile = DATA_HOME_OVERRIDE + "/" + domainUid + "/_WLS_ADMIN-SERVER000000.DAT";
    waitForFileExistsInPod(domainNamespace, adminServerPodName, defaultAdminDataFile);

    // check in managed server pod, the custom data file for JMS and default managed server datafile are created
    // in DATA_HOME_OVERRIDE
    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = domainUid + "-" + MANAGED_SERVER_NAME_BASE + i;
      String customDataFile = DATA_HOME_OVERRIDE + "/" + domainUid + "/FILESTORE-0@MANAGED-SERVER" + i + "000000.DAT";
      waitForFileExistsInPod(domainNamespace, managedServerPodName, customDataFile);

      String defaultMSDataFile = DATA_HOME_OVERRIDE + "/" + domainUid + "/_WLS_MANAGED-SERVER" + i + "000000.DAT";
      waitForFileExistsInPod(domainNamespace, managedServerPodName, defaultMSDataFile);
    }
  }

  /**
   * Verify dataHome override in a domain with model in image type.
   * In this domain, dataHome is not specified in the domain custom resource
   * The domain contains JMS and File Store configuration
   * File store directory is set to /u01/customFileStore in the model file which should not be overridden
   * by dataHome
   * File store and JMS server are targeted to the WebLogic admin server
   * see resource/wdt-models/model-multiclusterdomain-sampleapp-wls.yaml
   */
  @Test
  @DisplayName("Test dataHome override in a domain with model in image type")
  void testDataHomeOverrideMiiDomain() {

    assertDomainNotNull(miiDomain);
    String domainUid = miiDomain.getSpec().getDomainUid();
    String domainNamespace = miiDomain.getMetadata().getNamespace();

    // check in admin server pod, there is a data file for JMS server created in /u01/customFileStore
    String dataFileToCheck = "/u01/customFileStore/FILESTORE-0000000.DAT";
    String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;
    waitForFileExistsInPod(domainNamespace, adminServerPodName, dataFileToCheck);

    // check in admin server pod, the default admin server data file is in default data store
    String defaultAdminDataFile =
        "/u01/domains/" + domainUid + "/servers/admin-server/data/store/default/_WLS_ADMIN-SERVER000000.DAT";
    waitForFileExistsInPod(domainNamespace, adminServerPodName, defaultAdminDataFile);

    // check in managed server pod, there is no custom data file for JMS is created
    for (int i = 1; i <= replicaCount; i++) {
      for (int j = 1; j <= NUMBER_OF_CLUSTERS_MIIDOMAIN; j++) {
        String managedServerPodName = domainUid + "-cluster-" + j + "-" + MANAGED_SERVER_NAME_BASE + i;
        String customDataFile = "/u01/customFileStore/FILESTORE-0@MANAGED-SERVER" + i + "000000.DAT";
        assertFalse(assertDoesNotThrow(() ->
                doesFileExistInPod(domainNamespace, managedServerPodName, customDataFile),
            String.format("exception thrown when checking file %s exists in pod %s in namespace %s",
                customDataFile, managedServerPodName, domainNamespace)),
            String.format("found file %s in pod %s in namespace %s, expect not exist",
                customDataFile, managedServerPodName, domainNamespace));

        String defaultMSDataFile = "/u01/domains/" + domainUid + "/servers/cluster-" + j + "-managed-server" + i
            + "/data/store/default/_WLS_CLUSTER-" + j + "-MANAGED-SERVER" + i + "000000.DAT";
        waitForFileExistsInPod(domainNamespace, managedServerPodName, defaultMSDataFile);
      }
    }
  }

  /**
   * Verify dataHome override in a domain with domain on PV type.
   * In this domain, dataHome is set to empty string in the domain custom resource
   * The domain contains JMS and File Store configuration
   * File store directory is set to /u01/customFileStore in the model file which should not be overridden
   * by dataHome
   * File store and JMS server are targeted to the WebLogic admin server
   * see resource/wdt-models/domain-onpv-wdt-model.yaml
   */
  @Test
  @DisplayName("Test dataHome override in a domain with domain on PV type")
  void testDataHomeOverrideDomainOnPV() {

    assertDomainNotNull(domainOnPV);
    String domainUid = domainOnPV.getSpec().getDomainUid();
    String domainNamespace = domainOnPV.getMetadata().getNamespace();

    // check in admin server pod, there is a data file for JMS server created in /u01/customFileStore
    String dataFileToCheck = "/u01/customFileStore/FILESTORE-0000000.DAT";
    String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;
    waitForFileExistsInPod(domainNamespace, adminServerPodName, dataFileToCheck);

    // check in admin server pod, the default admin server data file is in default data store
    String defaultAdminDataFile =
        "/u01/shared/domains/" + domainUid + "/servers/admin-server/data/store/default/_WLS_ADMIN-SERVER000000.DAT";
    waitForFileExistsInPod(domainNamespace, adminServerPodName, defaultAdminDataFile);

    // check in managed server pod, there is no custom data file for JMS is created
    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = domainUid + "-" + MANAGED_SERVER_NAME_BASE + i;
      String customDataFile = "/u01/customFileStore/FILESTORE-0@MANAGED-SERVER" + i + "000000.DAT";
      assertFalse(assertDoesNotThrow(() ->
              doesFileExistInPod(domainNamespace, managedServerPodName, customDataFile),
          String.format("exception thrown when checking file %s exists in pod %s in namespace %s",
              customDataFile, managedServerPodName, domainNamespace)),
          String.format("found file %s in pod %s in namespace %s, expect not exist",
              customDataFile, managedServerPodName, domainNamespace));

      String defaultMSDataFile = "/u01/shared/domains/" + domainUid + "/servers/managed-server" + i
          + "/data/store/default/_WLS_MANAGED-SERVER" + i + "000000.DAT";
      waitForFileExistsInPod(domainNamespace, managedServerPodName, defaultMSDataFile);
    }
  }

  /**
   * Test rolling restart for a multi-clusters domain.
   * Make sure pods are restarted only once.
   * Verify all pods are terminated and restarted only once
   * Rolling restart triggered by changing:
   * imagePullPolicy: IfNotPresent --> imagePullPolicy: Never
   * Verify domain changed event is logged.
   */
  @Test
  @DisplayName("Verify server pods are restarted only once by changing the imagePullPolicy in multi-cluster domain")
  void testMiiMultiClustersRollingRestart() {
    OffsetDateTime timestamp = now();

    // get the original domain resource before update
    Domain domain1 = assertDoesNotThrow(() -> getDomainCustomResource(miiDomainUid, miiDomainNamespace),
            String.format("getDomainCustomResource failed with ApiException when tried to get domain %s "
                    + "in namespace %s", miiDomainUid, miiDomainNamespace));
    assertNotNull(domain1, "Got null domain resource");
    assertNotNull(domain1.getSpec(), domain1 + "/spec is null");

    //change imagePullPolicy: IfNotPresent --> imagePullPolicy: Never
    StringBuffer patchStr = new StringBuffer("[{")
            .append("\"op\": \"replace\",")
            .append(" \"path\": \"/spec/imagePullPolicy\",")
            .append("\"value\": \"")
            .append("Never")
            .append("\"}]");

    boolean cmPatched = patchDomainResource(miiDomainUid, miiDomainNamespace, patchStr);
    assertTrue(cmPatched, "patchDomainCustomResource(imagePullPolicy) failed");

    domain1 = assertDoesNotThrow(() -> getDomainCustomResource(miiDomainUid, miiDomainNamespace),
            String.format("getDomainCustomResource failed with ApiException when tried to get domain %s "
                    + "in namespace %s", miiDomainUid, miiDomainNamespace));
    assertNotNull(domain1, "Got null domain resource after patching");
    assertNotNull(domain1.getSpec(), domain1 + "/spec is null");

    //verify domain changed event is logged
    testUntil(
        checkDomainEvent(opNamespace, miiDomainNamespace, miiDomainUid, DOMAIN_CHANGED, "Normal", timestamp),
        logger,
        "domain event {0} to be logged",
        DOMAIN_CHANGED);

    // wait for longer time for DomainCompleted event
    testUntil(
        withLongRetryPolicy,
        checkDomainEvent(opNamespace, miiDomainNamespace, miiDomainUid, DOMAIN_COMPLETED, "Normal", timestamp),
        logger,
        DOMAIN_COMPLETED);

    // Verify that pod termination and started events are logged only once for each managed server in each cluster
    for (int i = 1; i <= NUMBER_OF_CLUSTERS_MIIDOMAIN; i++) {
      for (int j = 1; j <= replicaCount; j++) {
        String managedServerPodName =
            miiDomainUid + "-" + CLUSTER_NAME_PREFIX + i + "-" + MANAGED_SERVER_NAME_BASE + j;

        logger.info("Checking that managed server pod {0} is terminated and restarted once in namespace {1}",
            managedServerPodName, miiDomainNamespace);
        testUntil(
            checkPodEventLoggedOnce(miiDomainNamespace, managedServerPodName, POD_TERMINATED, timestamp),
            logger,
            "event {0} to be logged for pod {1}",
            POD_TERMINATED,
            managedServerPodName);
        testUntil(
            checkPodEventLoggedOnce(miiDomainNamespace, managedServerPodName, POD_STARTED, timestamp),
            logger,
            "event {0} to be logged for pod {1}",
            POD_STARTED,
            managedServerPodName);
      }
    }
  }

  /**
   * Generate a steam of Domain objects used in parameterized tests.
   * @return stream of oracle.weblogic.domain.Domain objects
   */
  private static Stream<Domain> domainProvider() {
    return domains.stream();
  }

  /**
   * Uninstall NGINX release.
   * Delete cluster role and cluster role binding used for WLDF.
   */
  @AfterAll
  public void tearDownAll() {
    if (System.getenv("SKIP_CLEANUP") == null
        || (System.getenv("SKIP_CLEANUP") != null
        && System.getenv("SKIP_CLEANUP").equalsIgnoreCase("false"))) {
      // uninstall NGINX release
      if (nginxHelmParams != null) {
        assertThat(uninstallNginx(nginxHelmParams.getHelmParams()))
            .as("Test uninstallNginx returns true")
            .withFailMessage("uninstallNginx() did not return true")
            .isTrue();
      }

      for (Domain domain : domains) {
        assertDomainNotNull(domain);

        String domainNamespace = domain.getMetadata().getNamespace();

        // delete cluster role binding created for WLDF policy
        if (assertDoesNotThrow(
            () -> clusterRoleBindingExists(domainNamespace + "-" + WLDF_CLUSTER_ROLE_BINDING_NAME))) {
          assertTrue(deleteClusterRoleBinding(domainNamespace + "-" + WLDF_CLUSTER_ROLE_BINDING_NAME));
        }
      }

      // delete cluster role created for WLDF policy
      if (assertDoesNotThrow(() -> clusterRoleExists(WLDF_CLUSTER_ROLE_NAME))) {
        assertThat(assertDoesNotThrow(() -> deleteClusterRole(WLDF_CLUSTER_ROLE_NAME),
            "deleteClusterRole failed with ApiException"))
            .as("Test delete cluster role returns true")
            .withFailMessage("deleteClusterRole() did not return true")
            .isTrue();
      }
    }
  }

  /**
   * Create model in image domain with multiple clusters.
   *
   * @param domainNamespace namespace in which the domain will be created
   * @return oracle.weblogic.domain.Domain objects
   */
  private static Domain createMiiDomainWithMultiClusters(String domainNamespace) {

    // admin/managed server name here should match with WDT model yaml file
    String adminServerPodName = miiDomainUid + "-" + ADMIN_SERVER_NAME_BASE;

    // create docker registry secret to pull the image from registry
    // this secret is used only for non-kind cluster
    logger.info("Creating docker registry secret in namespace {0}", domainNamespace);
    createOcirRepoSecret(domainNamespace);

    String adminSecretName = "weblogic-credentials";
    // create secret for admin credentials
    logger.info("Creating secret for admin credentials");
    createSecretWithUsernamePassword(adminSecretName, domainNamespace,
        ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create encryption secret
    logger.info("Creating encryption secret");
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace,
        "weblogicenc", "weblogicenc");

    // construct the cluster list used for domain custom resource
    List<Cluster> clusterList = new ArrayList<>();
    for (int i = NUMBER_OF_CLUSTERS_MIIDOMAIN; i >= 1; i--) {
      clusterList.add(new Cluster()
          .clusterName(CLUSTER_NAME_PREFIX + i)
          .replicas(replicaCount)
          .serverStartState("RUNNING"));
    }

    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(miiDomainUid)
            .namespace(domainNamespace))
        .spec(new DomainSpec()
            .domainUid(miiDomainUid)
            .domainHome("/u01/domains/" + miiDomainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(OCIR_SECRET_NAME))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domainNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.security.SSL.ignoreHostnameVerification=true "
                        + "-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom "))
                .resources(new V1ResourceRequirements()
                    .limits(resourceLimit)
                    .requests(resourceRequest)))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .adminChannelPortForwardingEnabled(true)
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default-secure")
                        .nodePort(getNextFreePort()))
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(getNextFreePort()))))
            .clusters(clusterList)
            .configuration(new Configuration()
                .introspectorJobActiveDeadlineSeconds(300L)
                .model(new Model()
                    .domainType(WLS_DOMAIN_TYPE)
                    .runtimeEncryptionSecret(encryptionSecretName))));
    setPodAntiAffinity(domain);
    // create model in image domain
    logger.info("Creating model in image domain {0} in namespace {1} using docker image {2}",
        miiDomainUid, domainNamespace, miiImage);
    createDomainAndVerify(domain, domainNamespace);

    // check that admin server pod is ready and service exists in domain namespace
    logger.info("Checking that admin server pod {0} is ready and service exists in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReadyAndServiceExists(adminServerPodName, miiDomainUid, domainNamespace);

    // check the readiness for the managed servers in each cluster
    for (int i = 1; i <= NUMBER_OF_CLUSTERS_MIIDOMAIN; i++) {
      for (int j = 1; j <= replicaCount; j++) {
        String managedServerPodName =
            miiDomainUid + "-" + CLUSTER_NAME_PREFIX + i + "-" + MANAGED_SERVER_NAME_BASE + j;

        // check managed server pod is ready and service exists in the namespace
        logger.info("Checking that managed server pod {0} is ready and service exists in namespace {1}",
            managedServerPodName, domainNamespace);
        checkPodReadyAndServiceExists(managedServerPodName, miiDomainUid, domainNamespace);
      }
    }

    return domain;
  }

  /**
   * Create a domain in PV using WDT.
   *
   * @param domainNamespace namespace in which the domain will be created
   * @return oracle.weblogic.domain.Domain objects
   */
  private static Domain createDomainOnPvUsingWdt(String domainNamespace) {

    final String domainUid = "domainonpv" + "-" + domainNamespace.substring(3);
    final String adminServerPodName = domainUid + "-" + ADMIN_SERVER_NAME_BASE;

    Domain domain = DomainUtils.createDomainOnPvUsingWdt(domainUid, domainNamespace, wlSecretName, clusterName,
        replicaCount, ItMultiDomainModelsWithLoadBalancer.class.getSimpleName());

    // build application sample-app and opensessionapp
    List<String> appSrcDirList = new ArrayList<>();
    appSrcDirList.add(MII_BASIC_APP_NAME);
    appSrcDirList.add(WLDF_OPENSESSION_APP);

    for (String appName : appSrcDirList) {
      assertTrue(buildAppArchive(defaultAppParams()
              .srcDirList(Collections.singletonList(appName))
              .appName(appName)),
          String.format("Failed to create app archive for %s", appName));

      logger.info("Getting port for default channel");
      int defaultChannelPort = assertDoesNotThrow(()
          -> getServicePort(domainNamespace, getExternalServicePodName(adminServerPodName), "default"),
          "Getting admin server default port failed");
      logger.info("default channel port: {0}", defaultChannelPort);
      assertNotEquals(-1, defaultChannelPort, "admin server defaultChannelPort is not valid");

      //deploy application
      Path archivePath = get(ARCHIVE_DIR, "wlsdeploy", "applications", appName + ".ear");
      logger.info("Deploying webapp {0} to domain {1}", archivePath, domainUid);
      deployUsingWlst(adminServerPodName, Integer.toString(defaultChannelPort),
          ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT, clusterName + "," + ADMIN_SERVER_NAME_BASE, archivePath,
          domainNamespace);
    }

    return domain;
  }


  /**
   * Generate the curl command to access the sample app from the ingress controller.
   *
   * @param domainUid uid of the domain
   * @param domainNamespace the namespace in which the domain exists
   * @param clusterName WebLogic cluster name which is the backend of the ingress
   * @param appContextRoot the context root of the application
   * @return curl command string
   */
  private static String generateCurlCmd(String domainUid, String domainNamespace, String clusterName,
                                        String appContextRoot) {
    if (OKD) {
      String routeHost = getRouteHost(domainNamespace, domainUid + "-cluster-" + clusterName);
      logger.info("routeHost = {0}", routeHost);
      return String.format("curl -v --show-error --noproxy '*' http://%s/%s/index.jsp",
          routeHost, appContextRoot);

    } else {
      return String.format("curl -v --show-error --noproxy '*' -H 'host: %s' http://%s:%s/%s/index.jsp",
          domainUid + "." + domainNamespace + "." + clusterName + ".test",
          K8S_NODEPORT_HOST, nodeportshttp, appContextRoot);
    }
  }

  /**
   * Generate a server list which contains all managed servers in the cluster before scale.
   *
   * @param numClusters         number of clusters in the domain
   * @param clusterName         the name of the WebLogic cluster
   * @param replicasBeforeScale the replicas of WebLogic cluster before scale
   * @return list of managed servers in the cluster before scale
   */
  private static List<String> listManagedServersBeforeScale(int numClusters, String clusterName,
                                                            int replicasBeforeScale) {

    List<String> managedServerNames = new ArrayList<>();
    for (int i = 1; i <= replicasBeforeScale; i++) {
      if (numClusters <= 1) {
        managedServerNames.add(MANAGED_SERVER_NAME_BASE + i);
      } else {
        managedServerNames.add(clusterName + "-" + MANAGED_SERVER_NAME_BASE + i);
      }
    }

    return managedServerNames;
  }

  /**
   * Assert the specified domain and domain spec, metadata and clusters not null.
   * @param domain oracle.weblogic.domain.Domain object
   */
  private static void assertDomainNotNull(Domain domain) {
    assertNotNull(domain, "domain is null");
    assertNotNull(domain.getSpec(), domain + " spec is null");
    assertNotNull(domain.getMetadata(), domain + " metadata is null");
    assertNotNull(domain.getSpec().getClusters(), domain.getSpec() + " getClusters() is null");
  }

  /**
   * Generate the managed server pod name prefix.
   *
   * @param numClusters number of clusters in the domain
   * @param domainUid   uid of the domain
   * @param clusterName the cluster name of the domain
   * @return prefix of managed server pod name
   */
  private String generateMsPodNamePrefix(int numClusters, String domainUid, String clusterName) {
    String managedServerPodNamePrefix;
    if (numClusters <= 1) {
      managedServerPodNamePrefix = domainUid + "-" + MANAGED_SERVER_NAME_BASE;
    } else {
      managedServerPodNamePrefix = domainUid + "-" + clusterName + "-" + MANAGED_SERVER_NAME_BASE;
    }

    return managedServerPodNamePrefix;
  }

  /**
   * Create a script to kill server.
   * @return a File object
   * @throws IOException if can not create a file
   */
  private File createScriptToKillServer() throws IOException {
    File killServerScript = File.createTempFile("killserver", ".sh");
    //deletes the file when VM terminates
    killServerScript.deleteOnExit();
    try (FileWriter fw = new FileWriter(killServerScript)) {
      fw.write("#!/bin/bash\n");
      fw.write("jps\n");
      fw.write("jps | grep Server\n");
      fw.write("jps | grep Server | awk '{print $1}'\n");
      fw.write("kill -9 `jps | grep Server | awk '{print $1}'`");
    }
    killServerScript.setExecutable(true, false);
    return killServerScript;
  }

  /**
   * Check whether a file exists in a pod in the given namespace.
   *
   * @param namespace the Kubernetes namespace that the pod is in
   * @param podName the name of the Kubernetes pod in which the command is expected to run
   * @param fileName the filename to check
   * @return true if the file exists, otherwise return false
   */
  private Callable<Boolean> fileExistsInPod(String namespace, String podName, String fileName) {
    return () -> {
      return doesFileExistInPod(namespace, podName, fileName);
    };
  }

  /**
   * Wait for file existing in the pod in the given namespace up to 1 minute.
   * @param namespace the Kubernetes namespace that the pod is in
   * @param podName the name of the Kubernetes pod in which the command is expected to run
   * @param fileName the filename to check
   */
  private void waitForFileExistsInPod(String namespace, String podName, String fileName) {

    logger.info("Wait for file {0} existing in pod {1} in namespace {2}", fileName, podName, namespace);
    testUntil(
        assertDoesNotThrow(() -> fileExistsInPod(namespace, podName, fileName)),
        logger,
        "file {0} exists in pod {1} in namespace {2}",
        fileName,
        podName,
        namespace);
  }

  /**
   * Negative test case for creating a model-in-image domain without encryption secret created.
   * The admin server service/pod will not be created.
   * The error message should be logged in the operator log.
   *
   * @param domainUid the uid of the domain to be created
   * @param domainNamespace namespace in which the domain will be created
   */
  private void createMiiDomainNegative(String domainUid, String domainNamespace) {

    // create docker registry secret to pull the image from registry
    // this secret is used only for non-kind cluster
    logger.info("Creating docker registry secret in namespace {0}", domainNamespace);
    createOcirRepoSecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Creating secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    createSecretWithUsernamePassword(adminSecretName, domainNamespace, ADMIN_USERNAME_DEFAULT, ADMIN_PASSWORD_DEFAULT);

    // create the domain CR without encryption secret created
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domainNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHome("/u01/domains/" + domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(OCIR_SECRET_NAME))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domainNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom "))
                .resources(new V1ResourceRequirements()
                    .limits(resourceLimit)
                    .requests(resourceRequest)))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .adminService(new AdminService()
                    .addChannelsItem(new Channel()
                        .channelName("default")
                        .nodePort(getNextFreePort()))))
            .configuration(new Configuration()
                .model(new Model()
                    .domainType(WLS_DOMAIN_TYPE)
                    .runtimeEncryptionSecret(encryptionSecretName))));

    setPodAntiAffinity(domain);

    // create model in image domain
    logger.info("Creating model in image domain {0} in namespace {1} using docker image {2}",
        domainUid, domainNamespace, miiImage);
    createDomainAndVerify(domain, domainNamespace);
  }

  /**
   * Create mii image and push it to the registry.
   *
   * @return mii image created
   */
  private static String createAndPushMiiImage() {
    // create image with model files
    logger.info("Creating image with model file {0} and verify", wdtModelFileForMiiDomain);
    List<String> appSrcDirList = new ArrayList<>();
    appSrcDirList.add(MII_BASIC_APP_NAME);
    appSrcDirList.add(WLDF_OPENSESSION_APP);
    miiImage =
        createMiiImageAndVerify(miiImageName, Collections.singletonList(MODEL_DIR + "/" + wdtModelFileForMiiDomain),
            appSrcDirList, WEBLOGIC_IMAGE_NAME, WEBLOGIC_IMAGE_TAG, WLS_DOMAIN_TYPE, false);

    // docker login and push image to docker registry if necessary
    dockerLoginAndPushImageToRegistry(miiImage);

    return miiImage;
  }
}
