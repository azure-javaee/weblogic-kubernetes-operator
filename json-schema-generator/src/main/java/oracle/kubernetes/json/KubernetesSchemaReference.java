// Copyright (c) 2019, 2024, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.json;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public class KubernetesSchemaReference {
  private static final String K8S_SCHEMA_URL =
      "https://raw.githubusercontent.com/yannh/kubernetes-json-schema/master/v%s/_definitions.json";
  private static final String K8S_SCHEMA_CACHE = "caches/kubernetes-%s.json";
  private static final String K8S_MARKDOWN_LINK = "k8s%s.md";

  private final String version;

  private KubernetesSchemaReference(String version) {
    this.version = version;
  }

  public static KubernetesSchemaReference create(String version) {
    return new KubernetesSchemaReference(version);
  }

  URL getKubernetesSchemaUrl() throws MalformedURLException, URISyntaxException {
    return new URI(getUrlString()).toURL();
  }

  private String getUrlString() {
    return String.format(K8S_SCHEMA_URL, version);
  }

  boolean matchesUrl(String url) {
    return url.equals(getUrlString());
  }

  public URL getKubernetesSchemaCacheUrl() {
    return KubernetesSchemaReference.class.getResource(String.format(K8S_SCHEMA_CACHE, version));
  }

  String getK8sMarkdownLink() {
    return String.format(K8S_MARKDOWN_LINK, version);
  }
}
