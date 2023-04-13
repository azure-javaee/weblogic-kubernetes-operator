// Copyright (c) 2021, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1LoadBalancerIngress;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServicePort;
import oracle.weblogic.kubernetes.TestConstants;
import oracle.weblogic.kubernetes.actions.ActionConstants;
import oracle.weblogic.kubernetes.logging.LoggingFacade;

import static oracle.weblogic.kubernetes.TestConstants.KUBERNETES_CLI;
import static oracle.weblogic.kubernetes.assertions.impl.Kubernetes.getService;
import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PCAUtils {  
  
  /**
   * Retreive external IP from LoadBalancer.
   *
   * @param namespace namespace
   * @param lbName lb name
   * @return ip
   * @throws java.lang.Exception exception
   */
  public static String getLoadBalancerIP(String namespace, String lbName) throws Exception {
    LoggingFacade logger = getLogger();
    Map<String, String> labels = new HashMap<>();
    labels.put("app.kubernetes.io/name", "traefik");
    V1Service service = getService(lbName, labels, namespace);
    assertNotNull(service, "Can't find service with name " + lbName);
    logger.info("Found service with name {0} in {1} namespace ", lbName, namespace);
    List<V1LoadBalancerIngress> ingress = service.getStatus().getLoadBalancer().getIngress();
    if (ingress != null) {
      logger.info("LoadBalancer Ingress " + ingress.toString());
      V1LoadBalancerIngress lbIng = ingress.stream().filter(c
          -> !c.getIp().equals("pending")
      ).findAny().orElse(null);
      if (lbIng != null) {
        logger.info("LoadBalancer is created with external ip " + lbIng.getIp());
        return lbIng.getIp();
      }
    }
    return null;
  }
  
  /**
   * Retreive LoadBalancer port from service.
   *
   * @param namespace namespace
   * @param lbName lb name
   * @param portName web or webSecure
   * @return port
   * @throws java.lang.Exception exception
   */
  public static int getLoadBalancerPort(String namespace, String lbName, String portName) throws Exception {
    LoggingFacade logger = getLogger();
    Map<String, String> labels = new HashMap<>();
    labels.put("app.kubernetes.io/name", "traefik");
    V1Service service = getService(lbName, labels, namespace);
    assertNotNull(service, "Can't find service with name " + lbName);
    logger.info("Found service with name {0} in {1} namespace ", lbName, namespace);
    List<V1ServicePort> ports = service.getSpec().getPorts();
    if (ports != null) {
      logger.info("LoadBalancer Ingress " + ports.toString());
      V1ServicePort webport = ports.stream().filter(c -> !c.getName().equals(portName)).findAny().orElse(null);
      if (webport != null) {
        logger.info("LoadBalancer is web port " + webport.getPort());
        return webport.getPort();
      }
    }
    return -1;
  }

  /**
   * Create Traefik ingress routing rules.
   *
   * @param domainNamespace namespace
   * @param domainUid domainuid
   */
  public static void createTraefikIngressRoutingRules(String domainNamespace, String domainUid) {
    LoggingFacade logger = getLogger();
    logger.info("Creating ingress rules for domain traffic routing");
    Path srcFile = Paths.get(ActionConstants.RESOURCE_DIR, "traefik/traefik-ingress-rules-pca.yaml");
    Path dstFile = Paths.get(TestConstants.RESULTS_ROOT, "traefik/traefik-ingress-rules-pca.yaml");
    assertDoesNotThrow(() -> {
      Files.deleteIfExists(dstFile);
      Files.createDirectories(dstFile.getParent());
      Files.write(dstFile, Files.readString(srcFile).replaceAll("@NS@", domainNamespace)
          .replaceAll("@domainuid@", domainUid)
          .getBytes(StandardCharsets.UTF_8));
      logger.info(Files.readString(dstFile));
    });   
    String command = KUBERNETES_CLI + " create -f " + dstFile;
    logger.info("Running {0}", command);
    ExecResult result;
    try {
      result = ExecCommand.exec(command, true);
      String response = result.stdout().trim();
      logger.info("exitCode: {0}, \nstdout: {1}, \nstderr: {2}",
          result.exitValue(), response, result.stderr());
      assertEquals(0, result.exitValue(), "Command didn't succeed");
    } catch (IOException | InterruptedException ex) {
      logger.severe(ex.getMessage());
    }
  }
  
  /**
   * Verify the server MBEAN configuration through rest API.
   *
   * @param hostAndPort LB host and port
   * @param managedServer name of the managed server
   * @return true if MBEAN is found otherwise false
   *
   */
  public static boolean checkManagedServerConfiguration(String hostAndPort, String managedServer) {
    LoggingFacade logger = getLogger();
    ExecResult result;
    logger.info("url = {0}", hostAndPort);
    StringBuffer checkCluster = new StringBuffer("status=$(curl -sk --user weblogic:welcome1 ");
    checkCluster.append("https://" + hostAndPort)
        .append("/management/tenant-monitoring/servers/")
        .append(managedServer)
        .append(" --silent --show-error ")
        .append(" -o /dev/null")
        .append(" -w %{http_code});")
        .append("echo ${status}");
    logger.info("checkManagedServerConfiguration: curl command {0}",
        new String(checkCluster));
    try {
      result = exec(new String(checkCluster), true);
    } catch (Exception ex) {
      logger.info("Exception in checkManagedServerConfiguration() {0}", ex);
      return false;
    }
    logger.info("checkManagedServerConfiguration: curl command returned {0}", result.toString());
    return result.stdout().equals("200");
  }

}
