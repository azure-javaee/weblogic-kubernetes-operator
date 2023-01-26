/*
Copyright 2020 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client.util.generic;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.kubernetes.client.common.KubernetesType;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.util.ClientBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GenericKubernetesApiForCoreApiTest {

  @Rule public WireMockRule wireMockRule = new WireMockRule(8181);

  private JSON json = new JSON();
  private GenericKubernetesApi<V1Pod, V1PodList> podClient;

  @Before
  public void setup() throws IOException {
    ApiClient apiClient = new ClientBuilder().setBasePath("http://localhost:" + 8181).build();
    podClient =
        new GenericKubernetesApi<>(V1Pod.class, V1PodList.class, "", "v1", "pods", apiClient);
  }

  // test delete
  @Test
  public void deleteNamespacedPodReturningStatus() {
    V1Status status = new V1Status().kind("Status").code(200).message("good!");
    stubFor(
        delete(urlEqualTo("/api/v1/namespaces/default/pods/foo1"))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(status))));

    KubernetesApiResponse<V1Pod> deletePodResp = podClient.delete("default", "foo1", null);
    assertTrue(deletePodResp.isSuccess());
    assertEquals(status, deletePodResp.getStatus());
    assertNull(deletePodResp.getObject());
    verify(1, deleteRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods/foo1")));
  }

  @Test
  public void deleteNamespacedPodReturningDeletedObject() {
    V1Pod foo1 =
        new V1Pod().kind("Pod").metadata(new V1ObjectMeta().namespace("default").name("foo1"));

    stubFor(
        delete(urlEqualTo("/api/v1/namespaces/default/pods/foo1"))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(foo1))));

    KubernetesApiResponse<V1Pod> deletePodResp = podClient.delete("default", "foo1");
    assertTrue(deletePodResp.isSuccess());
    assertEquals(foo1, deletePodResp.getObject());
    assertNull(deletePodResp.getStatus());
    verify(1, deleteRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods/foo1")));
  }

  @Test
  public void deleteNamespacedPodReturningForbiddenStatus() {
    V1Status status = new V1Status().kind("Status").code(403).message("good!");

    stubFor(
        delete(urlEqualTo("/api/v1/namespaces/default/pods/foo1"))
            .willReturn(aResponse().withStatus(403).withBody(json.serialize(status))));

    KubernetesApiResponse<V1Pod> deletePodResp = podClient.delete("default", "foo1");
    assertFalse(deletePodResp.isSuccess());
    assertEquals(status, deletePodResp.getStatus());
    assertNull(deletePodResp.getObject());
    verify(1, deleteRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods/foo1")));
  }

  @Test
  public void listNamespacedPodReturningObject() {
    V1PodList podList = new V1PodList().kind("PodList").metadata(new V1ListMeta());

    stubFor(
        get(urlPathEqualTo("/api/v1/namespaces/default/pods"))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(podList))));
    KubernetesApiResponse<V1PodList> podListResp = podClient.list("default");
    assertTrue(podListResp.isSuccess());
    assertEquals(podList, podListResp.getObject());
    assertNull(podListResp.getStatus());
    verify(1, getRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods")));
  }

  @Test
  public void listClusterPodReturningObject() {
    V1PodList podList = new V1PodList().kind("PodList").metadata(new V1ListMeta());

    stubFor(
        get(urlPathEqualTo("/api/v1/pods"))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(podList))));
    KubernetesApiResponse<V1PodList> podListResp = podClient.list();
    assertTrue(podListResp.isSuccess());
    assertEquals(podList, podListResp.getObject());
    assertNull(podListResp.getStatus());
    verify(
        1,
        getRequestedFor(urlPathEqualTo("/api/v1/pods")).withQueryParam("watch", equalTo("false")));
  }

  @Test
  public void listClusterPodAsyncReturningObject() throws InterruptedException, ExecutionException {
    V1PodList podList = new V1PodList().kind("PodList").metadata(new V1ListMeta());

    stubFor(
        get(urlPathEqualTo("/api/v1/pods"))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(podList))));
    TestCallback<V1PodList> callback = new TestCallback<>();
    Future<KubernetesApiResponse<V1PodList>> podListFuture = podClient.listAsync(callback);
    KubernetesApiResponse<V1PodList> podListResp = callback.waitForAndGetResponse();
    assertTrue(podListResp.isSuccess());
    assertEquals(podList, podListResp.getObject());
    assertNull(podListResp.getStatus());
    assertTrue(podListFuture.isDone());
    assertFalse(podListFuture.isCancelled());
    assertEquals(podListResp, podListFuture.get());
    verify(
        1,
        getRequestedFor(urlPathEqualTo("/api/v1/pods")).withQueryParam("watch", equalTo("false")));
  }

  @Test
  public void createNamespacedPodReturningObject() {
    V1Pod foo1 =
        new V1Pod().kind("Pod").metadata(new V1ObjectMeta().namespace("default").name("foo1"));

    stubFor(
        post(urlEqualTo("/api/v1/namespaces/default/pods"))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(foo1))));
    KubernetesApiResponse<V1Pod> podListResp = podClient.create(foo1);
    assertTrue(podListResp.isSuccess());
    assertEquals(foo1, podListResp.getObject());
    assertNull(podListResp.getStatus());
    verify(1, postRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods")));
  }

  @Test
  public void updateNamespacedPodReturningObject() {
    V1Pod foo1 =
        new V1Pod().kind("Pod").metadata(new V1ObjectMeta().namespace("default").name("foo1"));

    stubFor(
        put(urlEqualTo("/api/v1/namespaces/default/pods/foo1"))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(foo1))));
    KubernetesApiResponse<V1Pod> podListResp = podClient.update(foo1);
    assertTrue(podListResp.isSuccess());
    assertEquals(foo1, podListResp.getObject());
    assertNull(podListResp.getStatus());
    verify(1, putRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods/foo1")));
  }

  @Test
  public void patchNamespacedPodReturningObject() {
    V1Patch v1Patch = new V1Patch("{}");
    V1Pod foo1 =
        new V1Pod().kind("Pod").metadata(new V1ObjectMeta().namespace("default").name("foo1"));
    stubFor(
        patch(urlEqualTo("/api/v1/namespaces/default/pods/foo1"))
            .withHeader("Content-Type", containing(V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(foo1))));
    KubernetesApiResponse<V1Pod> podPatchResp =
        podClient.patch("default", "foo1", V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH, v1Patch);

    assertTrue(podPatchResp.isSuccess());
    assertEquals(foo1, podPatchResp.getObject());
    assertNull(podPatchResp.getStatus());
    verify(1, patchRequestedFor(urlPathEqualTo("/api/v1/namespaces/default/pods/foo1")));
  }

  @Test
  public void patchNamespacedPodWithApiPrefix() {
    V1Patch v1Patch = new V1Patch("{}");
    V1Pod foo1 =
        new V1Pod().kind("Pod").metadata(new V1ObjectMeta().namespace("default").name("foo1"));
    // add api prefix
    String prefix = "/k8s/clusters/c-7q988";
    stubFor(
        patch(urlEqualTo(prefix + "/api/v1/namespaces/default/pods/foo1"))
            .withHeader("Content-Type", containing(V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH))
            .willReturn(aResponse().withStatus(200).withBody(json.serialize(foo1))));

    GenericKubernetesApi<V1Pod, V1PodList> rancherPodClient =
        new GenericKubernetesApi<>(
            V1Pod.class,
            V1PodList.class,
            "",
            "v1",
            "pods",
            new ClientBuilder().setBasePath("http://localhost:" + 8181 + prefix).build());
    KubernetesApiResponse<V1Pod> podPatchResp =
        rancherPodClient.patch(
            "default", "foo1", V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH, v1Patch);

    assertTrue(podPatchResp.isSuccess());
    assertEquals(foo1, podPatchResp.getObject());
    assertNull(podPatchResp.getStatus());
    verify(1, patchRequestedFor(urlPathEqualTo(prefix + "/api/v1/namespaces/default/pods/foo1")));
  }

  @Test
  public void testReadTimeoutShouldThrowException() {
    ApiClient apiClient = new ClientBuilder().setBasePath("http://localhost:" + 8181).build();
    apiClient.setHttpClient(
        apiClient
            .getHttpClient()
            .newBuilder()
            .readTimeout(1, TimeUnit.MILLISECONDS) // timeout everytime
            .build());
    stubFor(
        get(urlEqualTo("/api/v1/namespaces/foo/pods/test"))
            .willReturn(aResponse().withFixedDelay(99999).withStatus(200).withBody("")));
    podClient =
        new GenericKubernetesApi<>(V1Pod.class, V1PodList.class, "", "v1", "pods", apiClient);
    try {
      KubernetesApiResponse<V1Pod> response = podClient.get("foo", "test");
    } catch (Throwable t) {
      assertTrue(t.getCause() instanceof SocketTimeoutException);
      return;
    }
    fail("no exception happened");
  }

  static class TestCallback<ApiType extends KubernetesType> implements Consumer<KubernetesApiResponse<ApiType>> {
    final AtomicReference<KubernetesApiResponse<ApiType>> result = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void accept(KubernetesApiResponse<ApiType> apiTypeKubernetesApiResponse) {
      result.set(apiTypeKubernetesApiResponse);
      latch.countDown();
    }

    public KubernetesApiResponse<ApiType> waitForAndGetResponse() throws InterruptedException {
      latch.await();
      return result.get();
    }
  }
}
