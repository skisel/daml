// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.sandbox.bridge.validate

import com.daml.api.util.TimeProvider
import com.daml.error.ContextualizedErrorLogger
import com.daml.ledger.configuration.Configuration
import com.daml.ledger.offset.Offset
import com.daml.ledger.participant.state.v2.{CompletionInfo, Update}
import com.daml.ledger.sandbox.bridge.LedgerBridge.{
  fromOffset,
  partyAllocationSuccessMapper,
  successMapper,
  toOffset,
}
import com.daml.ledger.sandbox.bridge.SequencerState.LastUpdatedAt
import com.daml.ledger.sandbox.bridge._
import com.daml.ledger.sandbox.bridge.validate.ConflictCheckingLedgerBridge.{
  Sequence,
  Validation,
  _,
}
import com.daml.ledger.sandbox.domain.Rejection._
import com.daml.ledger.sandbox.domain.Submission.{AllocateParty, Config, Transaction}
import com.daml.ledger.sandbox.domain._
import com.daml.lf.data.Ref
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.transaction.{Transaction => LfTransaction}
import com.daml.logging.ContextualizedLogger
import com.daml.metrics.Timed
import com.daml.platform.server.api.validation.ErrorFactories
import com.daml.platform.store.appendonlydao.events._

import java.util.UUID
import scala.util.chaining._

/** Conflict checking with the in-flight commands,
  * assigns offsets and converts the accepted/rejected commands to updates.
  */
private[validate] class SequenceImpl(
    participantId: Ref.ParticipantId,
    timeProvider: TimeProvider,
    initialLedgerEnd: Offset,
    initialAllocatedParties: Set[Ref.Party],
    initialLedgerConfiguration: Option[Configuration],
    validatePartyAllocation: Boolean,
    bridgeMetrics: BridgeMetrics,
    errorFactories: ErrorFactories,
) extends Sequence {
  private[this] implicit val logger: ContextualizedLogger = ContextualizedLogger.get(getClass)

  @volatile private var offsetIdx = fromOffset(initialLedgerEnd)
  @volatile private var sequencerQueueState = SequencerState()(bridgeMetrics)
  @volatile private var allocatedParties = initialAllocatedParties
  @volatile private var ledgerConfiguration = initialLedgerConfiguration

  override def apply(): Validation[(Offset, PreparedSubmission)] => Iterable[(Offset, Update)] =
    in => {
      Timed.value(
        bridgeMetrics.Stages.sequence, {
          offsetIdx = offsetIdx + 1L
          val newOffset = toOffset(offsetIdx)
          val recordTime = timeProvider.getCurrentTimestamp

          val update = in match {
            case Left(rejection) =>
              rejection.toCommandRejectedUpdate(recordTime)
            case Right((_, NoOpPreparedSubmission(submission))) =>
              processNonTransactionSubmission(submission)
            case Right((noConflictUpTo, txSubmission: PreparedTransactionSubmission)) =>
              sequentialTransactionValidation(
                noConflictUpTo,
                newOffset,
                recordTime,
                txSubmission,
              )
          }

          Iterable(newOffset -> update)
        },
      )
    }

  private def processNonTransactionSubmission(submission: Submission): Update =
    submission match {
      case AllocateParty(hint, displayName, submissionId) =>
        val party = Ref.Party.assertFromString(hint.getOrElse(UUID.randomUUID().toString))
        if (allocatedParties(party))
          logger.warn(
            s"Found duplicate party submission with ID $party for submissionId ${Some(submissionId)}"
          )(submission.loggingContext)

        allocatedParties = allocatedParties + party
        // TODO SoX: Do not forward a successful party allocation update on duplicates
        partyAllocationSuccessMapper(party, displayName, submissionId, participantId)
      case Config(maxRecordTime, submissionId, config) =>
        val recordTime = timeProvider.getCurrentTimestamp
        if (recordTime > maxRecordTime)
          Update.ConfigurationChangeRejected(
            recordTime = recordTime,
            submissionId = submissionId,
            participantId = participantId,
            proposedConfiguration = config,
            rejectionReason = s"Configuration change timed out: $maxRecordTime > $recordTime",
          )
        else {
          val expectedGeneration = ledgerConfiguration.map(_.generation).map(_ + 1L)
          if (expectedGeneration.forall(_ == config.generation)) {
            ledgerConfiguration = Some(config)
            Update.ConfigurationChanged(
              recordTime = recordTime,
              submissionId = submissionId,
              participantId = participantId,
              newConfiguration = config,
            )
          } else
            Update.ConfigurationChangeRejected(
              recordTime = recordTime,
              submissionId = submissionId,
              participantId = participantId,
              proposedConfiguration = config,
              rejectionReason =
                s"Generation mismatch: expected=$expectedGeneration, actual=${config.generation}",
            )
        }

      case other => successMapper(other, offsetIdx, participantId)
    }

  private def sequentialTransactionValidation(
      noConflictUpTo: Offset,
      newOffset: LastUpdatedAt,
      recordTime: Timestamp,
      txSubmission: PreparedTransactionSubmission,
  ) = {
    val submitterInfo = txSubmission.submission.submitterInfo

    withErrorLogger(submitterInfo.submissionId) { implicit errorLogger =>
      for {
        _ <- checkTimeModel(txSubmission.submission, recordTime, ledgerConfiguration)
        completionInfo = submitterInfo.toCompletionInfo()
        _ <- validateParties(
          allocatedParties,
          txSubmission.transactionInformees,
          completionInfo,
        )
        _ <- conflictCheckWithInFlight(
          keysState = sequencerQueueState.keyState,
          consumedContractsState = sequencerQueueState.consumedContractsState,
          keyInputs = txSubmission.keyInputs,
          inputContracts = txSubmission.inputContracts,
          completionInfo = completionInfo,
        )
      } yield ()
    }(txSubmission.submission.loggingContext, logger)
      .fold(
        _.toCommandRejectedUpdate(recordTime),
        { _ =>
          // Update the sequencer state
          sequencerQueueState = sequencerQueueState
            .dequeue(noConflictUpTo)
            .enqueue(newOffset, txSubmission.updatedKeys, txSubmission.consumedContracts)

          successMapper(txSubmission.submission, offsetIdx, participantId)
        },
      )
  }

  private def conflictCheckWithInFlight(
      keysState: Map[Key, (Option[ContractId], LastUpdatedAt)],
      consumedContractsState: Set[ContractId],
      keyInputs: KeyInputs,
      inputContracts: Set[ContractId],
      completionInfo: CompletionInfo,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Validation[Unit] =
    inputContracts.intersect(consumedContractsState) pipe {
      case alreadyArchived if alreadyArchived.nonEmpty =>
        Left(UnknownContracts(alreadyArchived)(completionInfo, errorFactories))
      case _ =>
        keyInputs
          .foldLeft[Validation[Unit]](Right(())) {
            case (Right(_), (key, LfTransaction.KeyCreate)) =>
              keysState.get(key) match {
                case None | Some((None, _)) => Right(())
                case Some((Some(_), _)) => Left(DuplicateKey(key)(completionInfo, errorFactories))
              }
            case (Right(_), (key, LfTransaction.NegativeKeyLookup)) =>
              keysState.get(key) match {
                case None | Some((None, _)) => Right(())
                case Some((Some(actual), _)) =>
                  Left(InconsistentContractKey(None, Some(actual))(completionInfo, errorFactories))
              }
            case (Right(_), (key, LfTransaction.KeyActive(cid))) =>
              keysState.get(key) match {
                case None | Some((Some(`cid`), _)) => Right(())
                case Some((other, _)) =>
                  Left(InconsistentContractKey(other, Some(cid))(completionInfo, errorFactories))
              }
            case (left, _) => left
          }
    }

  private def validateParties(
      allocatedParties: Set[Ref.Party],
      transactionInformees: Set[Ref.Party],
      completionInfo: CompletionInfo,
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Validation[Unit] =
    if (validatePartyAllocation) {
      val unallocatedInformees = transactionInformees diff allocatedParties
      Either.cond(
        unallocatedInformees.isEmpty,
        (),
        UnallocatedParties(unallocatedInformees.toSet)(completionInfo, errorFactories),
      )
    } else Right(())

  private def checkTimeModel(
      transaction: Transaction,
      recordTime: Timestamp,
      ledgerConfiguration: Option[Configuration],
  )(implicit contextualizedErrorLogger: ContextualizedErrorLogger): Validation[Unit] = {
    val completionInfo = transaction.submitterInfo.toCompletionInfo()
    ledgerConfiguration
      .toRight(Rejection.NoLedgerConfiguration(completionInfo, errorFactories))
      .flatMap(configuration =>
        configuration.timeModel
          .checkTime(
            transaction.transactionMeta.ledgerEffectiveTime,
            recordTime,
          )
          .left
          .map(Rejection.InvalidLedgerTime(completionInfo, _)(errorFactories))
      )
  }
}
