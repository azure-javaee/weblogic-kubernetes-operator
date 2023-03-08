// Copyright (c) 2018, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.calls;

import java.util.Collections;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.common.KubernetesType;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import oracle.kubernetes.common.logging.MessageKeys;
import oracle.kubernetes.operator.builders.CallParams;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.DomainCondition;
import oracle.kubernetes.weblogic.domain.model.DomainResource;

import static oracle.kubernetes.operator.KubernetesConstants.HTTP_CONFLICT;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_FORBIDDEN;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_NOT_FOUND;
import static oracle.kubernetes.operator.KubernetesConstants.HTTP_UNAUTHORIZED;
import static oracle.kubernetes.operator.calls.RequestStep.CONTINUE;
import static oracle.kubernetes.operator.calls.RequestStep.FIBER_TIMEOUT;
import static oracle.kubernetes.operator.calls.RequestStep.accessContinue;
import static oracle.kubernetes.weblogic.domain.model.DomainConditionType.FAILED;
import static oracle.kubernetes.weblogic.domain.model.DomainFailureReason.KUBERNETES;

/**
 * Step to receive response of Kubernetes API server call.
 *
 * <p>Most implementations will only need to implement {@link #onSuccess(Packet, KubernetesApiResponse)}.
 *
 * @param <T> Response type
 */
public abstract class ResponseStep<T extends KubernetesType> extends Step {
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private final Step conflictStep;
  private Step previousStep = null;

  /** Constructor specifying no next step. */
  protected ResponseStep() {
    this(null);
  }

  /**
   * Constructor specifying next step.
   *
   * @param nextStep Next step
   */
  protected ResponseStep(Step nextStep) {
    this(null, nextStep);
  }

  /**
   * Constructor specifying conflict and next step.
   *
   * @param conflictStep Conflict step
   * @param nextStep Next step
   */
  protected ResponseStep(Step conflictStep, Step nextStep) {
    super(nextStep);
    this.conflictStep = conflictStep;
  }

  public final void setPrevious(Step previousStep) {
    this.previousStep = previousStep;
  }

  @Override
  public final Void apply(Packet packet) {
    Void nextAction = getActionForKubernetesApiResponse(packet);

    if (nextAction == null) { // no call response, since call timed-out
      nextAction = getPotentialRetryAction(packet);
    }

    if (previousStep != nextAction.getNext()) { // not a retry, clear out old response
      packet.remove(CONTINUE);
      packet.getComponents().remove(RequestStep.RESPONSE_COMPONENT_NAME);
    }

    return nextAction;
  }

  private Void getActionForKubernetesApiResponse(Packet packet) {
    return Optional.ofNullable(getKubernetesApiResponse(packet)).map(c -> fromKubernetesApiResponse(packet, c)).orElse(null);
  }

  @SuppressWarnings("unchecked")
  private KubernetesApiResponse<T> getKubernetesApiResponse(Packet packet) {
    return packet.getSpi(KubernetesApiResponse.class);
  }

  private Void fromKubernetesApiResponse(Packet packet, KubernetesApiResponse<T> callResponse) {
    return callResponse.isFailure() ? onFailure(packet, callResponse) : onSuccess(packet, callResponse);
  }

  private Void getPotentialRetryAction(Packet packet) {
    return Optional.ofNullable(doPotentialRetry(conflictStep, packet, getKubernetesApiResponse(packet))).orElse(doEnd(packet));
  }

  /**
   * Returns next action that can be used to get the next batch of results from a list search that
   * specified a "continue" value, if any; otherwise, returns next.
   *
   * @param callResponse Call response
   * @param packet Packet
   * @return Next action for list continue
   */
  protected final Void doContinueListOrNext(KubernetesApiResponse<T> callResponse, Packet packet) {
    return doContinueListOrNext(callResponse, packet, getNext());
  }

  /**
   * Returns next action that can be used to get the next batch of results from a list search that
   * specified a "continue" value, if any; otherwise, returns next.
   *
   * @param callResponse Call response
   * @param packet Packet
   * @param next Next step, if no continuation
   * @return Next action for list continue
   */
  protected final Void doContinueListOrNext(KubernetesApiResponse<T> callResponse, Packet packet, Step next) {
    String cont = accessContinue(callResponse.getObject());
    if (cont != null) {
      packet.put(CONTINUE, cont);
      // Since the continue value is present, invoking the original request will return
      // the next window of data.
      return resetRetryStrategyAndReinvokeRequest(packet);
    }
    if (callResponse.getObject() instanceof KubernetesListObject) {
      return doNext(next, packet).withDebugComment((KubernetesListObject)callResponse.getObject(), this::toComment);
    } else {
      return doNext(next, packet);
    }
  }

  private String toComment(KubernetesListObject list) {
    return Optional.ofNullable(list).map(KubernetesListObject::getItems).orElse(Collections.emptyList()).stream()
          .map(this::toElementString).collect(Collectors.joining(", "));
  }

  private String toElementString(KubernetesObject object) {
    return toElementType(object) + ' ' + object.getMetadata().getName();
  }

  @Nonnull
  private String toElementType(KubernetesObject object) {
    final String[] parts = object.getClass().getSimpleName().split("(?<!^)(?=[A-Z])");
    return parts.length == 1 ? parts[0].toLowerCase() : parts[1].toLowerCase();
  }

  /**
   * Returns next action when the Kubernetes API server call should be retried, null otherwise.
   *
   * @param conflictStep Conflict step
   * @param packet Packet
   * @param callResponse the response from the call
   * @return Next action for retry or null, if no retry is warranted
   */
  private Void doPotentialRetry(Step conflictStep, Packet packet, KubernetesApiResponse<T> callResponse) {
    return Optional.ofNullable(packet.getSpi(RetryStrategy.class))
        .map(rs -> rs.doPotentialRetry(conflictStep, packet,
            Optional.ofNullable(callResponse).map(KubernetesApiResponse::getStatusCode).orElse(FIBER_TIMEOUT)))
        .orElseGet(() -> logNoRetry(packet, callResponse));
  }

  private Void logNoRetry(Packet packet, KubernetesApiResponse<T> callResponse) {
    if ((callResponse != null)
        && (callResponse.getStatusCode() != HTTP_NOT_FOUND)
        && (callResponse.getStatusCode() != HTTP_CONFLICT)) {
      addDomainFailureStatus(packet, callResponse.getRequestParams(), callResponse.getE());
      if (LOGGER.isWarningEnabled()) {
        LOGGER.warning(
            MessageKeys.ASYNC_NO_RETRY,
            Optional.ofNullable(previousStep).map(Step::identityHash).orElse(""),
            Optional.of(callResponse.getRequestParams()).map(r -> r.call).orElse("--no call--"),
            callResponse.getExceptionString(),
            callResponse.getStatusCode(),
            callResponse.getHeadersString(),
            Optional.of(callResponse.getRequestParams()).map(r -> r.namespace).orElse(""),
            Optional.of(callResponse.getRequestParams()).map(r -> r.name).orElse(""),
            Optional.of(callResponse.getRequestParams()).map(r -> r.body)
                .map(b -> LoggingFactory.getJson().serialize(b)).orElse(""),
            Optional.of(callResponse.getRequestParams()).map(RequestParams::getCallParams)
                .map(CallParams::getFieldSelector).orElse(""),
            Optional.of(callResponse.getRequestParams()).map(RequestParams::getCallParams)
                .map(CallParams::getLabelSelector).orElse(""),
            Optional.of(callResponse.getRequestParams()).map(RequestParams::getCallParams)
                .map(CallParams::getResourceVersion).orElse(""),
            Optional.of(callResponse.getE()).map(ApiException::getResponseBody).orElse(""));
      }
    }
    return null;
  }

  private void addDomainFailureStatus(Packet packet, RequestParams requestParams, ApiException apiException) {
    DomainPresenceInfo.fromPacket(packet)
        .map(DomainPresenceInfo::getDomain)
        .ifPresent(domain -> updateFailureStatus(domain, requestParams, apiException));
  }

  private void updateFailureStatus(
      @Nonnull DomainResource domain, RequestParams requestParams, ApiException apiException) {
    DomainCondition condition = new DomainCondition(FAILED).withReason(KUBERNETES)
        .withMessage(createMessage(requestParams, apiException));
    addFailureStatus(domain, condition);
  }

  private void addFailureStatus(@Nonnull DomainResource domain, DomainCondition condition) {
    domain.getOrCreateStatus().addCondition(condition);
  }

  private String createMessage(RequestParams requestParams, ApiException apiException) {
    return requestParams.createFailureMessage(apiException);
  }

  /**
   * Resets any retry strategy, such as a failed retry count and invokes the request again. This
   * will be useful for patterns such as list requests that include a "continue" value.
   * @param packet Packet
   * @return Next action for the original request
   */
  private Void resetRetryStrategyAndReinvokeRequest(Packet packet) {
    RetryStrategy retryStrategy = packet.getSpi(RetryStrategy.class);
    if (retryStrategy != null) {
      retryStrategy.reset();
    }
    return doNext(previousStep, packet);
  }

  /**
   * Callback for API server call failure. The ApiException, HTTP status code and response headers
   * are provided in callResponse; however, these will be null or 0 when the client timed-out.
   *
   * @param packet Packet
   * @param callResponse the result of the call
   * @return Next action for fiber processing, which may be a retry
   */
  public Void onFailure(Packet packet, KubernetesApiResponse<T> callResponse) {
    return onFailure(null, packet, callResponse);
  }

  /**
   * Callback for API server call failure. The ApiException and HTTP status code and response
   * headers are provided; however, these will be null or 0 when the client simply timed-out.
   *
   * <p>The default implementation tests if the request could be retried and, if not, ends fiber
   * processing.
   *
   * @param conflictStep Conflict step
   * @param packet Packet
   * @param callResponse the result of the call
   * @return Next action for fiber processing, which may be a retry
   */
  public Void onFailure(Step conflictStep, Packet packet, KubernetesApiResponse<T> callResponse) {
    return Optional.ofNullable(doPotentialRetry(conflictStep, packet, callResponse))
          .orElseGet(() -> onFailureNoRetry(packet, callResponse));
  }

  protected Void onFailureNoRetry(Packet packet, KubernetesApiResponse<T> callResponse) {
    return doTerminate(createTerminationException(packet, callResponse), packet);
  }

  /**
   * Create an exception to be passed to the doTerminate call.
   *
   * @param packet Packet for creating the exception
   * @param callResponse KubernetesApiResponse for creating the exception
   * @return An Exception to be passed to the doTerminate call
   */
  protected Throwable createTerminationException(Packet packet, KubernetesApiResponse<T> callResponse) {
    return UnrecoverableErrorBuilder.createExceptionFromFailedCall(callResponse);
  }

  protected boolean isNotAuthorizedOrForbidden(KubernetesApiResponse<T> callResponse) {
    return callResponse.getStatusCode() == HTTP_UNAUTHORIZED || callResponse.getStatusCode() == HTTP_FORBIDDEN;
  }

  protected boolean isForbidden(KubernetesApiResponse<T> callResponse) {
    return callResponse.getStatusCode() == HTTP_FORBIDDEN;
  }

  /**
   * Callback for API server call success.
   *
   * @param packet Packet
   * @param callResponse the result of the call
   * @return Next action for fiber processing
   */
  public Void onSuccess(Packet packet, KubernetesApiResponse<T> callResponse) {
    throw new IllegalStateException("Must be overridden, if called");
  }
}