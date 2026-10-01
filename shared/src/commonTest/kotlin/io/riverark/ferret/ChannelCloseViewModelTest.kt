package io.riverark.ferret

import io.riverark.ferret.core.cardano.ChannelConstants
import io.riverark.ferret.core.cardano.ChannelDatum
import io.riverark.ferret.core.cardano.ChannelDatumStage
import io.riverark.ferret.core.cardano.LedgerUtxo
import io.riverark.ferret.core.channel.ChannelAction
import io.riverark.ferret.core.channel.ChannelChainObservation
import io.riverark.ferret.core.channel.ChannelCollectionV4
import io.riverark.ferret.core.channel.ChannelPayload
import io.riverark.ferret.core.channel.ChannelPreview
import io.riverark.ferret.core.channel.ChannelRemoteResult
import io.riverark.ferret.core.channel.ChannelSnapshot
import io.riverark.ferret.core.channel.ChannelStateChangedException
import io.riverark.ferret.core.channel.ChannelReturnNotReadyException
import io.riverark.ferret.core.channel.PreparedChannelOperation
import io.riverark.ferret.core.channel.ProtocolKeytag
import io.riverark.ferret.core.model.AssetAmount
import io.riverark.ferret.core.model.AssetPricing
import io.riverark.ferret.core.model.CardanoNetwork
import io.riverark.ferret.core.model.ChannelAsset
import io.riverark.ferret.core.model.ChannelState
import io.riverark.ferret.core.model.Lovelace
import io.riverark.ferret.core.model.OperationState
import io.riverark.ferret.core.model.WalletId
import io.riverark.ferret.feature.wallet.ChannelCloseViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelCloseViewModelTest {
    @Test fun refreshAndCancelNeverAuthorizeTransactions() = runBlocking {
        val fixture = Fixture()
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        assertNotNull(fixture.vm.state.value.preview)
        fixture.vm.clearPreview()
        fixture.vm.stopRefreshing()
        fixture.vm.refreshNow()
        assertNull(fixture.vm.state.value.preview)
        assertEquals(1, fixture.closeReviews)
        assertEquals(0, fixture.returnReviews)
        assertEquals(0, fixture.submissions)
    }

    @Test fun duplicatePreviewAndPreviewCancellationNeverCreateAnOperation() = runBlocking {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.close = { release.await(); preview }
        fixture.vm.refreshNow()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { fixture.vm.previewNow(true) }
        fixture.vm.previewNow(true)
        fixture.vm.submitNow()
        fixture.vm.refreshNow()
        assertEquals(1, fixture.closeReviews)
        assertEquals(1, fixture.loads)
        job.cancelAndJoin()
        assertFalse(fixture.vm.state.value.busy)
        assertNull(fixture.vm.state.value.operationId)
        assertNull(fixture.vm.state.value.preview)
        assertEquals(0, fixture.submissions)
    }

    @Test fun rapidRequestsAndRefreshCannotOverlapSubmission() = runBlocking {
        val fixture = Fixture()
        val release = CompletableDeferred<Unit>()
        fixture.submit = { release.await(); operation.operationId }
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { fixture.vm.submitNow() }
        fixture.vm.submitNow()
        fixture.vm.previewNow(true)
        fixture.vm.refreshNow()
        fixture.vm.clearPreview()
        fixture.vm.stopRefreshing()
        assertEquals(1, fixture.submissions)
        assertEquals(1, fixture.loads)
        assertEquals(operation.operationId, fixture.vm.state.value.operationId)
        assertTrue(job.isActive)
        release.complete(Unit)
        job.join()
        assertFalse(fixture.vm.state.value.busy)
    }

    @Test fun cancelledSubmissionRequiresDurableStatusRecovery() = runBlocking {
        val fixture = Fixture()
        fixture.submit = { CompletableDeferred<Unit>().await(); operation.operationId }
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { fixture.vm.submitNow() }
        job.cancelAndJoin()
        assertEquals(operation.operationId, fixture.vm.state.value.operationId)
        assertNotNull(fixture.vm.state.value.error)
        fixture.vm.previewNow(true)
        assertEquals(1, fixture.closeReviews)
        fixture.channel = fixture.channel.copy(pending = operation)
        fixture.vm.refreshNow()
        assertEquals(operation.operationId, fixture.vm.state.value.operationId)
        fixture.channel = fixture.channel.copy(pending = null)
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        assertEquals(2, fixture.closeReviews)
        assertEquals(1, fixture.submissions)
    }

    @Test fun uncertainSubmissionAndFailedReloadCannotOfferAnotherConfirmation() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.submit = { error("transport lost after journaling") }
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        fixture.vm.submitNow()
        fixture.channel = fixture.channel.copy(state = ChannelState.Closing("close"), pending = operation)
        fixture.vm.refreshNow()
        assertEquals(operation.operationId, fixture.vm.state.value.operationId)
        fixture.loadFailure = true
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        fixture.vm.submitNow()
        assertEquals(1, fixture.submissions)
        assertNull(fixture.vm.state.value.preview)
        assertNotNull(fixture.vm.state.value.error)
    }

    @Test fun verifiedPreJournalAbsenceRestoresExplicitReview() = runBlocking {
        val fixture = Fixture()
        fixture.submit = { error("rejected before journaling") }
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        fixture.vm.submitNow()
        fixture.vm.previewNow(true)
        assertEquals(1, fixture.closeReviews)
        fixture.vm.refreshNow()
        assertNull(fixture.vm.state.value.operationId)
        fixture.vm.previewNow(true)
        assertEquals(2, fixture.closeReviews)
        assertEquals(1, fixture.submissions)
    }

    @Test fun durableFailureRestoresReviewButNonterminalHistoryKeepsStatusOnly() = runBlocking {
        val fixture = Fixture()
        fixture.submit = { error("unknown") }
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        fixture.vm.submitNow()
        fixture.channel = fixture.channel.copy(history = listOf(result(OperationState.PENDING_RECONCILIATION)))
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        assertEquals(1, fixture.closeReviews)
        fixture.channel = fixture.channel.copy(history = listOf(result(OperationState.FAILED)))
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        assertEquals(2, fixture.closeReviews)
        assertNull(fixture.vm.state.value.operationId)
    }

    @Test fun changedStageOrInputInvalidatesUnsubmittedPreview() = runBlocking {
        val fixture = Fixture()
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        fixture.channel = fixture.channel.copy(chainObservation = observation(false))
        fixture.vm.refreshNow()
        assertNull(fixture.vm.state.value.preview)
        fixture.vm.previewNow(true)
        fixture.channel = fixture.channel.copy(state = ChannelState.Closed)
        fixture.vm.refreshNow()
        assertNull(fixture.vm.state.value.preview)
        fixture.vm.submitNow()
        assertEquals(0, fixture.submissions)
    }

    @Test fun changedAndNotReadyErrorsReloadWithoutAutomaticReview() = runBlocking {
        val fixture = Fixture()
        fixture.close = { fixture.channel = fixture.channel.copy(state = ChannelState.Closed); throw ChannelStateChangedException() }
        fixture.vm.refreshNow()
        fixture.vm.previewNow(true)
        assertEquals(ChannelState.Closed, fixture.vm.state.value.channel?.state)
        assertNull(fixture.vm.state.value.preview)
        assertNotNull(fixture.vm.state.value.error)
        fixture.channel = fixture.channel.copy(chainObservation = observation(true))
        fixture.vm.refreshNow()
        fixture.returnFunds = {
            fixture.channel = fixture.channel.copy(chainObservation = observation(false))
            throw ChannelReturnNotReadyException(100)
        }
        fixture.vm.previewNow(false)
        assertFalse(fixture.vm.state.value.channel!!.chainObservation!!.canReturn)
        assertNull(fixture.vm.state.value.preview)
        assertEquals(1, fixture.closeReviews)
        assertEquals(1, fixture.returnReviews)
        assertEquals(0, fixture.submissions)
    }

    @Test fun pollingStopsAtReadyWithoutReviewingOrSigning() = runBlocking {
        val fixture = Fixture()
        fixture.channel = fixture.channel.copy(state = ChannelState.Closed, chainObservation = observation(false))
        val waits = mutableListOf<Long>()
        fixture.vm.refreshWhilePending {
            waits += it
            fixture.channel = fixture.channel.copy(chainObservation = observation(true))
        }
        assertEquals(listOf(20_000L), waits)
        assertEquals(2, fixture.loads)
        assertEquals(0, fixture.returnReviews)
        assertEquals(0, fixture.submissions)
    }

    @Test fun pollingCancellationAndResumeAfterErrorRemainReadOnly() = runBlocking {
        val fixture = Fixture()
        fixture.channel = fixture.channel.copy(state = ChannelState.Closed)
        val waiting = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            fixture.vm.refreshWhilePending { waiting.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        waiting.await()
        job.cancelAndJoin()
        val stoppedLoads = fixture.loads
        fixture.loadFailure = true
        try {
            fixture.vm.refreshWhilePending { throw CancellationException() }
        } catch (_: CancellationException) { }
        assertNotNull(fixture.vm.state.value.error)
        fixture.loadFailure = false
        fixture.channel = fixture.channel.copy(chainObservation = observation(true))
        fixture.vm.refreshWhilePending { error("ready must stop polling") }
        assertEquals(stoppedLoads + 2, fixture.loads)
        assertNull(fixture.vm.state.value.error)
        assertEquals(0, fixture.submissions)
    }

    @Test fun confirmedReturnKeepsPollingUntilRetainedOperationIsSettled() = runBlocking {
        val fixture = Fixture()
        fixture.channel = fixture.channel.copy(state = ChannelState.FundsReturned("returned"), confirmedReturnOperation = operation)
        var waits = 0
        fixture.vm.refreshWhilePending {
            waits++
            fixture.channel = fixture.channel.copy(confirmedReturnOperation = null)
        }
        assertEquals(1, waits)
        assertEquals(2, fixture.loads)
        assertEquals(0, fixture.submissions)
    }

    private class Fixture {
        var channel = ChannelSnapshot(keytag, ada, ChannelState.Open("channel"))
        var loadFailure = false
        var loads = 0
        var closeReviews = 0
        var returnReviews = 0
        var submissions = 0
        var close: suspend () -> ChannelPreview = { preview }
        var returnFunds: suspend () -> ChannelPreview = { preview }
        var submit: suspend () -> String = { operation.operationId }
        val vm = ChannelCloseViewModel(walletId, keytag, {
            loads++
            if (loadFailure) error("offline")
            ChannelCollectionV4(walletId = walletId, catalogDigest = digest, channels = mapOf(keytag.value to channel))
        }, { _, _ -> closeReviews++; close() }, { _, _ -> returnReviews++; returnFunds() },
            { _, _ -> submissions++; submit() }, { 123 })
    }

    private companion object {
        val digest = "0".repeat(64)
        val walletId = WalletId("mainnet-${"a".repeat(56)}")
        val keytag = ProtocolKeytag("bb".repeat(33))
        val ada = ChannelAsset("ada", null, null, 6, AssetPricing.ADA, digest)
        val amount = AssetAmount(ada, 3_000_000)
        val operation = PreparedChannelOperation("close-id", "hash", keytag, ada, ChannelAction.Close,
            preparedAtEpochMillis = 1, payload = ChannelPayload.Protocol(byteArrayOf(1)), priorChannelState = ChannelState.Open("channel"))
        val preview = ChannelPreview(operation, amount, AssetAmount(ada, 200_000), AssetAmount(ada, 300_000), null,
            AssetAmount(ada, 2_000_000), AssetAmount(ada, 2_000_000), amount, CardanoNetwork.MAINNET, AssetAmount(ada, 5_000_000))
        fun result(status: OperationState) = ChannelRemoteResult(operation.operationId, operation.intentHash, keytag, ada,
            state = ChannelState.Open("channel"), status = status)
        fun observation(ready: Boolean) = ChannelChainObservation(
            LedgerUtxo("a".repeat(64), 0, "channel-address", Lovelace(5_000_000)),
            ChannelDatum("a".repeat(56), ChannelConstants("b".repeat(64), "c".repeat(64), "d".repeat(64), 60_000, ada),
                ChannelDatumStage.Closed(0, elapseAtEpochMillis = 100)), 5, 100, ready,
        )
    }
}
