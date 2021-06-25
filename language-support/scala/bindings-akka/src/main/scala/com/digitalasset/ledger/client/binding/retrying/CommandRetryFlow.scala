// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.client.binding.retrying

import java.time.temporal.TemporalAmount

import akka.NotUsed
import akka.stream.{FlowShape, OverflowStrategy}
import akka.stream.scaladsl.{Flow, GraphDSL, MergePreferred, Partition}
import com.codahale.metrics.Counter
import com.daml.api.util.TimeProvider
import com.daml.ledger.api.refinements.ApiTypes.Party
import com.daml.ledger.api.v1.command_submission_service.SubmitRequest
import com.daml.ledger.api.v1.completion.Completion
import com.daml.ledger.client.services.commands.CommandClient
import com.daml.util.Ctx
import com.daml.util.akkastreams.MaxInFlight
import com.google.rpc.Code
import com.google.rpc.status.Status
import scalaz.syntax.tag._

import scala.concurrent.{ExecutionContext, Future}

object CommandRetryFlow {

  type In[C] = Ctx[C, SubmitRequest]
  type Out[C] = Ctx[C, Completion]
  type SubmissionFlowType[C] = Flow[In[C], Out[C], NotUsed]
  type CreateRetryFn[C] = (RetryInfo[C], Completion) => SubmitRequest

  private val RETRY_PORT = 0
  private val PROPAGATE_PORT = 1

  def apply[C](
      party: Party,
      commandClient: CommandClient,
      timeProvider: TimeProvider,
      maxRetryTime: TemporalAmount,
      createRetry: CreateRetryFn[C],
  )(implicit ec: ExecutionContext): Future[SubmissionFlowType[C]] =
    for {
      submissionFlow <- commandClient.trackCommandsUnbounded[RetryInfo[C]](List(party.unwrap))
      submissionFlowWithoutMat = submissionFlow.mapMaterializedValue(_ => NotUsed)
      maxInFlight = MaxInFlight[In[C], Out[C]](
        commandClient.config.maxCommandsInFlight,
        new Counter,
        new Counter,
      )
      graph = createGraph(
        submissionFlowWithoutMat,
        timeProvider,
        maxRetryTime,
        commandClient.config.maxCommandsInFlight,
        createRetry,
      )
    } yield maxInFlight.join(wrapGraph(graph, timeProvider))

  def wrapGraph[C](
      graph: SubmissionFlowType[RetryInfo[C]],
      timeProvider: TimeProvider,
  ): SubmissionFlowType[C] =
    Flow[In[C]]
      .map(RetryInfo.wrap(timeProvider))
      .via(graph)
      .map(RetryInfo.unwrap)

  def createGraph[C](
      commandSubmissionFlow: SubmissionFlowType[RetryInfo[C]],
      timeProvider: TimeProvider,
      maxRetryTime: TemporalAmount,
      retryBufferSize: Int,
      createRetry: CreateRetryFn[C],
  ): SubmissionFlowType[RetryInfo[C]] =
    Flow
      .fromGraph(GraphDSL.create(commandSubmissionFlow) { implicit b => commandSubmission =>
        import GraphDSL.Implicits._

        val mergeRetriesPreferred =
          b.add(MergePreferred[In[RetryInfo[C]]](secondaryPorts = 1, eagerComplete = true))

        val retryDecider = b.add(
          Partition[Out[RetryInfo[C]]](
            outputPorts = 2,
            {
              case Ctx(
                    RetryInfo(request, nrOfRetries, firstSubmissionTime, _),
                    Completion(_, Some(status: Status), _, _),
                    _,
                  ) =>
                if (status.code == Code.OK_VALUE) {
                  PROPAGATE_PORT
                } else if (
                  (firstSubmissionTime plus maxRetryTime) isBefore timeProvider.getCurrentTime
                ) {
                  RetryLogger.logStopRetrying(request, status, nrOfRetries, firstSubmissionTime)
                  PROPAGATE_PORT
                } else if (RETRYABLE_ERROR_CODES.contains(status.code)) {
                  RetryLogger.logNonFatal(request, status, nrOfRetries)
                  RETRY_PORT
                } else {
                  RetryLogger.logFatal(request, status, nrOfRetries)
                  PROPAGATE_PORT
                }
              case Ctx(_, Completion(commandId, _, _, _), _) =>
                statusNotFoundError(commandId)
            },
          )
        )

        val convertToRetry = b.add(Flow[Out[RetryInfo[C]]].map {
          case Ctx(retryInfo, failedCompletion, telemetryContext) =>
            Ctx(retryInfo.newRetry, createRetry(retryInfo, failedCompletion), telemetryContext)
        })

        // This buffer, together with the mergePreferred flow, is important to break the cycle around commandSubmission.
        // Without this setup, mergeRetriesPreferred would backpressure all the way to retryDecider, effectively
        // stopping the entire flow, i.e. command processing.
        val buffer =
          b.add(Flow[Out[RetryInfo[C]]].buffer(retryBufferSize, OverflowStrategy.backpressure))

        // format: off
        mergeRetriesPreferred.out ~> commandSubmission ~> retryDecider.in
        mergeRetriesPreferred.in(RETRY_PORT) <~ convertToRetry <~ buffer <~ retryDecider.out(RETRY_PORT)
        // format: on

        FlowShape(mergeRetriesPreferred.in(PROPAGATE_PORT), retryDecider.out(PROPAGATE_PORT))
      })

  private[retrying] val RETRYABLE_ERROR_CODES =
    Set(Code.RESOURCE_EXHAUSTED_VALUE, Code.UNAVAILABLE_VALUE)

  private def statusNotFoundError(commandId: String): Int =
    throw new RuntimeException(s"Status for command $commandId is missing.")

}
