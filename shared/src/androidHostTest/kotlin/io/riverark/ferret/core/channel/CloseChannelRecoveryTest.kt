package io.riverark.ferret.core.channel

import io.riverark.ferret.core.cardano.*
import io.riverark.ferret.core.model.*
import io.riverark.ferret.core.network.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CloseChannelRecoveryTest {
    @Test fun adaAndNativeJourneysRequireBothExplicitTransactionsAndPreserveSiblings() = runBlocking {
        for (native in listOf(false, true)) for (empty in listOf(false, true)) {
            val f = CloseTransactionFixture()
            val asset = if (native) f.usda else f.catalog.ada
            val quantity = if (empty) 0L else if (native) 40L else 3_000_000L
            val selected = f.install(asset, quantity)
            val sibling = ChannelSnapshot(f.keytag("02"), f.catalog.ada, ChannelState.Open("sibling"),
                spendableBalance = AssetAmount(f.catalog.ada, 9_000_000))
            val h = Harness(f, selected, sibling)
            var repository = h.reopen()
            val close = repository.previewClose(f.profile.id, selected.keytag)
            assertEquals(0, f.signingCalls)
            repository.submit(f.profile.id, close)
            assertIs<ChannelState.Closing>(h.entry.state)
            assertNotNull(h.entry.pending)
            val signedClose = requireNotNull(h.entry.pending)
            val projection = f.project(signedClose, 4)
            f.transaction = projection
            h.exposeOutputs(projection)
            h.lookup = { h.result(it, OperationState.COMPLETED) }
            repository.reconcileAll(f.profile.id)
            assertIs<ChannelState.Closing>(h.entry.state)
            assertNull(h.entry.returnProof)
            assertFalse(h.entry.chainObservation?.canReturn == true)
            f.transaction = projection.copy(depth = 5)
            repository = h.reopen()
            repository.reconcileAll(f.profile.id)
            assertEquals(ChannelState.Closed, h.entry.state)
            assertNull(h.entry.pending)
            assertEquals(sibling, h.stored.channels.getValue(sibling.keytag.value))
            assertEquals(1, h.mutations.size)
            assertEquals(1, f.signingCalls)

            f.ledger = f.ledger.copy(currentSlot = f.intent(signedClose).validUntil + 60)
            repository.reconcileAll(f.profile.id)
            assertTrue(requireNotNull(h.entry.chainObservation).canReturn)
            val terminal = repository.previewReturnFunds(f.profile.id, selected.keytag)
            assertEquals(CloseChannelStep.ELAPSE, f.intent(terminal.operation).step)
            assertEquals(1, f.signingCalls)
            repository.submit(f.profile.id, terminal)
            assertEquals(ChannelState.Ending, h.entry.state)
            val signedReturn = requireNotNull(h.entry.pending)
            val returned = f.project(signedReturn, 4)
            f.transaction = returned
            h.exposeOutputs(returned)
            repository.reconcileAll(f.profile.id)
            assertEquals(ChannelState.Ending, h.entry.state)
            assertNull(h.entry.returnProof)
            f.transaction = returned.copy(depth = 5)
            repository.reconcileAll(f.profile.id)
            assertEquals(ChannelState.FundsReturned(returned.id), h.entry.state)
            assertEquals(0L, h.entry.spendableBalance.baseUnits)
            assertNull(h.entry.chainObservation)
            val proof = requireNotNull(h.entry.returnProof)
            assertEquals(if (native) quantity else f.intent(signedReturn).channelInput.lovelace.value, proof.returnedAmount.baseUnits)
            assertEquals(f.intent(signedReturn).channelInput.lovelace, proof.releasedAda)
            assertEquals(signedReturn.operationId, h.entry.confirmedReturnOperation?.operationId)
            assertEquals(sibling, h.stored.channels.getValue(sibling.keytag.value))
            assertEquals(2, f.signingCalls)
            assertEquals(2, h.mutations.size)
        }
    }

    @Test fun unsignedInterruptionRestoresExactPriorStageWithoutSigningOrPosting() = runBlocking {
        for (close in listOf(true, false)) {
            val f = CloseTransactionFixture()
            val initial = f.install(stage = if (close) ChannelDatumStage.Opened(0) else ChannelDatumStage.Responded(0))
            val h = Harness(f, initial)
            var repository = h.reopen()
            val preview = if (close) repository.previewClose(f.profile.id, initial.keytag)
                else repository.previewReturnFunds(f.profile.id, initial.keytag)
            h.writeFailure = { if (it.channels.getValue(initial.keytag.value).pending != null) error("unsigned Drive interruption") }
            assertFails { repository.submit(f.profile.id, preview) }
            assertTrue(f.payload(requireNotNull(h.entry.pending)).signedTransaction.isEmpty())
            h.writeFailure = {}
            repository = h.reopen()
            repository.reconcileAll(f.profile.id)
            assertNull(h.entry.pending)
            assertEquals(initial.state, h.entry.state)
            assertEquals(initial.spendableBalance, h.entry.spendableBalance)
            assertEquals(OperationState.FAILED, h.entry.history.single().status)
            assertEquals(0, f.signingCalls)
            assertTrue(h.mutations.isEmpty())
        }
    }

    @Test fun signedWriteAheadInterruptionReplaysOnlyOriginalBytesAfterAuthoritativeAbsence() = runBlocking {
        val f = CloseTransactionFixture()
        val initial = f.install()
        val h = Harness(f, initial)
        var repository = h.reopen()
        val preview = repository.previewClose(f.profile.id, initial.keytag)
        h.writeFailure = { collection ->
            collection.channels.getValue(initial.keytag.value).pending?.let {
                if (f.payload(it).signedTransaction.isNotEmpty()) error("signed Drive interruption")
            }
        }
        assertFails { repository.submit(f.profile.id, preview) }
        val original = requireNotNull(h.entry.pending)
        assertEquals(1, f.signingCalls)
        assertTrue(h.mutations.isEmpty())
        h.writeFailure = {}
        h.lookup = { error("transport uncertainty") }
        repository = h.reopen()
        assertFails { repository.reconcile(f.profile.id, initial.keytag) }
        assertTrue(h.mutations.isEmpty())
        h.lookup = { null }
        repository.reconcile(f.profile.id, initial.keytag)
        assertEquals(1, h.mutations.size)
        assertEquals(original.operationId, h.mutations.single().operationId)
        assertContentEquals(f.payload(original).signedTransaction, f.payload(h.mutations.single()).signedTransaction)
        assertEquals(1, f.signingCalls)
        h.lookup = { h.result(it, OperationState.SUBMITTED) }
        h.reopen().reconcileAll(f.profile.id)
        assertEquals(1, h.mutations.size)
    }

    @Test fun expiryNeedsRemoteAbsenceConnectorAbsenceAndExactUnspentInput() = runBlocking {
        for (remoteAbsent in listOf(false, true)) {
            val f = CloseTransactionFixture()
            val signed = f.signedOperation(close = true)
            val initial = ChannelSnapshot(signed.keytag, signed.asset, ChannelState.Closing(signed.operationId),
                pending = signed.copy(state = OperationState.PENDING_RECONCILIATION),
                spendableBalance = requireNotNull(signed.priorSpendableBalance))
            val h = Harness(f, initial)
            f.transaction = null
            f.ledger = f.ledger.copy(currentSlot = f.intent(signed).validUntil)
            h.lookup = { if (remoteAbsent) null else h.result(it, OperationState.SUBMITTED) }
            h.reopen().reconcileAll(f.profile.id)
            if (remoteAbsent) {
                assertNull(h.entry.pending)
                assertEquals(signed.priorChannelState, h.entry.state)
                assertEquals(OperationState.FAILED, h.entry.history.single().status)
            } else {
                assertNotNull(h.entry.pending)
                assertIs<ChannelState.Closing>(h.entry.state)
            }
            assertTrue(h.mutations.isEmpty())
            assertEquals(1, f.signingCalls)
        }
    }

    @Test fun savedProofRequiresFreshChainEvidenceBeforeFinalDriveCommitOnRestart() = runBlocking {
        val h = terminalHarness()
        val f = h.f
        val signed = requireNotNull(h.entry.pending)
        val projection = f.project(signed)
        f.transaction = projection
        h.exposeOutputs(projection)
        h.commitFailure = { if (it.channels.getValue(signed.keytag.value).state is ChannelState.FundsReturned) error("final Drive interruption") }
        assertFails { h.reopen().reconcile(f.profile.id, signed.keytag) }
        assertEquals(ChannelState.Ending, h.entry.state)
        assertEquals(OperationState.COMPLETED, h.entry.pending?.state)
        assertEquals(projection.id, h.entry.returnProof?.transactionId)
        assertEquals(OperationState.COMPLETED, h.entry.history.single().status)
        h.commitFailure = {}
        f.transaction = projection.copy(depth = 4)
        h.reopen().reconcileAll(f.profile.id)
        assertEquals(ChannelState.Ending, h.entry.state)
        assertNotNull(h.entry.pending)
        assertFalse(h.entry.returnProof?.confirmationDepth?.let { it >= 5 } == true)
        f.transaction = projection
        h.reopen().reconcileAll(f.profile.id)
        assertIs<ChannelState.FundsReturned>(h.entry.state)
        assertNull(h.entry.pending)
        assertTrue(h.mutations.isEmpty())
        assertEquals(1, f.signingCalls)
    }

    @Test fun rollbackDepthAbsenceAndContradictionRecoverOriginalOperationWithoutRemoteAction() = runBlocking {
        for (rollback in listOf("depth", "missing", "contradictory")) {
            val h = terminalHarness()
            val f = h.f
            val signed = requireNotNull(h.entry.pending)
            val projection = f.project(signed)
            f.transaction = projection
            h.exposeOutputs(projection)
            h.reopen().reconcileAll(f.profile.id)
            assertIs<ChannelState.FundsReturned>(h.entry.state)
            val lookupCount = h.lookups
            h.lookup = { error("retained return must not consult adaptor") }
            f.transaction = when (rollback) {
                "depth" -> projection.copy(depth = 4)
                "missing" -> null
                else -> projection.copy(outputs = emptyList())
            }
            f.ledger = f.ledger.copy(utxos = f.ledger.utxos + f.intent(signed).channelInput)
            h.reopen().reconcileAll(f.profile.id)
            assertEquals(ChannelState.Ending, h.entry.state)
            assertEquals(signed.operationId, h.entry.pending?.operationId)
            assertContentEquals(f.payload(signed).signedTransaction, f.payload(requireNotNull(h.entry.pending)).signedTransaction)
            assertEquals(signed.priorSpendableBalance, h.entry.spendableBalance)
            assertEquals(OperationState.PENDING_RECONCILIATION, h.entry.history.single().status)
            assertEquals(signed.operationId, h.entry.confirmedReturnOperation?.operationId)
            assertEquals(lookupCount, h.lookups)
            f.transaction = projection
            h.exposeOutputs(projection)
            h.reopen().reconcileAll(f.profile.id)
            assertIs<ChannelState.FundsReturned>(h.entry.state)
            assertEquals(5L, h.entry.returnProof?.confirmationDepth)
            f.transaction = projection.copy(depth = 2_160)
            h.reopen().reconcileAll(f.profile.id)
            assertNull(h.entry.confirmedReturnOperation)
            assertEquals(2_160L, h.entry.returnProof?.confirmationDepth)
            assertTrue(h.mutations.isEmpty())
            assertEquals(1, f.signingCalls)
        }
    }

    @Test fun provedPhaseTwoFailuresRestorePriorStateAndRetainActualCollateralLossAcrossRestart() = runBlocking {
        for (native in listOf(false, true)) for (step in CloseChannelStep.entries) {
            val close = step == CloseChannelStep.CLOSE
            val f = CloseTransactionFixture()
            val stage = when (step) {
                CloseChannelStep.CLOSE -> ChannelDatumStage.Opened(0)
                CloseChannelStep.ELAPSE -> ChannelDatumStage.Closed(0, elapseAtEpochMillis = f.deadline)
                CloseChannelStep.END -> ChannelDatumStage.Responded(0)
            }
            val initial = f.install(if (native) f.usda else f.catalog.ada, if (native) 40 else 3_000_000, stage)
            if (step == CloseChannelStep.ELAPSE) f.ledger = f.ledger.copy(currentSlot = 4_492_980)
            val preview = if (close) f.transactions.previewClose(f.profile.id, initial, f.operationId)
                else f.transactions.previewReturnFunds(f.profile.id, initial, f.operationId)
            val signed = f.transactions.sign(f.profile.id, preview.operation)
            val h = Harness(f, initial.copy(state = if (close) ChannelState.Closing(signed.operationId) else ChannelState.Ending,
                pending = signed.copy(state = OperationState.SUBMITTED)))
            f.transaction = f.project(signed, phaseTwoFailure = true)
            val expectedFailure = requireNotNull(f.transactions.confirmOperation(f.profile.id, signed)).first
            h.commitFailure = { error("failure evidence final Drive interruption") }
            assertFails { h.reopen().reconcile(f.profile.id, signed.keytag) }
            assertEquals(OperationState.FAILED, h.entry.pending?.state)
            assertEquals(expectedFailure.failureMessage, h.entry.history.single().failureMessage)
            h.commitFailure = {}
            h.reopen().reconcileAll(f.profile.id)
            assertNull(h.entry.pending)
            assertEquals(initial.state, h.entry.state)
            assertEquals(initial.spendableBalance, h.entry.spendableBalance)
            assertNull(h.entry.returnProof)
            assertNull(h.entry.confirmedReturnOperation)
            val history = h.entry.history.single()
            assertEquals(OperationState.FAILED, history.status)
            assertEquals(f.payload(signed).expectedTransactionId, history.transactionId)
            assertEquals(f.intent(signed).step, history.closeStep)
            assertEquals(5L, history.confirmationDepth)
            assertEquals(expectedFailure.failureMessage, history.failureMessage)
            assertNotNull(history.failureMessage)
            h.reopen().reconcileAll(f.profile.id)
            assertEquals(history, h.entry.history.single())
            assertTrue(h.mutations.isEmpty())
            assertEquals(1, f.signingCalls)
        }
    }

    @Test fun unavailableHostPreservesCloseRecordsAndRejectsExplicitOperationsBeforeFinancialAction() = runBlocking {
        val h = terminalHarness()
        val original = h.stored
        val f = h.f
        val repository = h.reopen(enabled = false)
        repository.reconcileAll(f.profile.id)
        assertEquals(original, h.stored)
        assertFails { repository.previewClose(f.profile.id, h.entry.keytag) }
        assertFails { repository.previewReturnFunds(f.profile.id, h.entry.keytag) }
        assertFails { repository.reconcile(f.profile.id, h.entry.keytag) }
        assertEquals(original, h.stored)
        assertEquals(0, h.lookups)
        assertTrue(h.mutations.isEmpty())
        assertEquals(1, f.signingCalls)
    }

    @Test fun observationOnlyRefreshFindsExternalStagesWithoutWriterAndMissingOutputNeverMeansReturned() = runBlocking {
        val f = CloseTransactionFixture()
        val initial = f.install()
        val h = Harness(f, initial)
        h.writerFailure = true
        val repository = h.reopen()
        repository.reconcileAll(f.profile.id)
        assertIs<ChannelState.Open>(h.entry.state)
        assertNotNull(h.entry.chainObservation)
        f.install(stage = ChannelDatumStage.Closed(0, elapseAtEpochMillis = f.deadline))
        repository.reconcileAll(f.profile.id)
        assertEquals(ChannelState.Closed, h.entry.state)
        assertFalse(requireNotNull(h.entry.chainObservation).canReturn)
        f.install(stage = ChannelDatumStage.Responded(0, listOf(f.pending(f.deadline))))
        repository.reconcileAll(f.profile.id)
        assertEquals(ChannelState.Responded, h.entry.state)
        assertFalse(requireNotNull(h.entry.chainObservation).canReturn)
        f.install(stage = ChannelDatumStage.Responded(0))
        repository.reconcileAll(f.profile.id)
        assertTrue(requireNotNull(h.entry.chainObservation).canReturn)
        f.ledger = f.ledger.copy(utxos = f.ledger.utxos.filter { it.address != MAINNET.validatorAddress })
        repository.reconcileAll(f.profile.id)
        assertEquals(ChannelState.Responded, h.entry.state)
        assertNull(h.entry.chainObservation)
        assertEquals(initial.spendableBalance, h.entry.spendableBalance)
        assertNull(h.entry.returnProof)
        assertEquals(0, h.writerRequests)
        assertTrue(h.mutations.isEmpty())
        assertEquals(0, f.signingCalls)
    }

    @Test fun staleWriterAndWriteAheadFailureCannotBypassSigningGuards() = runBlocking {
        for (stale in listOf(true, false)) {
            val f = CloseTransactionFixture()
            val initial = f.install()
            val h = Harness(f, initial)
            val repository = h.reopen()
            val preview = repository.previewClose(f.profile.id, initial.keytag)
            h.writerFailure = stale
            if (!stale) h.writeFailure = { error("Drive unavailable") }
            assertFails { repository.submit(f.profile.id, preview) }
            assertEquals(0, f.signingCalls)
            assertTrue(h.mutations.isEmpty())
            assertEquals(initial.spendableBalance, h.entry.spendableBalance)
            if (stale) assertEquals(initial, h.entry)
        }
    }

    @Test fun submissionTransportInterruptionRestartsWithLookupNotAnotherPost() = runBlocking {
        val f = CloseTransactionFixture()
        val initial = f.install()
        val h = Harness(f, initial)
        val repository = h.reopen()
        val preview = repository.previewClose(f.profile.id, initial.keytag)
        h.mutateFailure = { error("response lost after acceptance") }
        assertFails { repository.submit(f.profile.id, preview) }
        val signed = requireNotNull(h.entry.pending)
        assertEquals(OperationState.PENDING_RECONCILIATION, signed.state)
        assertEquals(1, h.mutations.size)
        h.mutateFailure = {}
        h.lookup = { h.result(it, OperationState.SUBMITTED) }
        h.reopen().reconcileAll(f.profile.id)
        assertEquals(signed.operationId, h.entry.pending?.operationId)
        assertEquals(1, h.mutations.size)
        assertEquals(1, f.signingCalls)
    }

    @Test fun rejectedReturnRestoresRespondedRatherThanOpen() = runBlocking {
        val h = terminalHarness()
        val signed = requireNotNull(h.entry.pending)
        h.lookup = { h.result(it, OperationState.FAILED).copy(state = ChannelState.Absent, failureMessage = "Rejected") }
        h.reopen().reconcileAll(h.f.profile.id)
        assertNull(h.entry.pending)
        assertEquals(ChannelState.Responded, h.entry.state)
        assertEquals(signed.priorSpendableBalance, h.entry.spendableBalance)
        assertEquals(OperationState.FAILED, h.entry.history.single().status)
        assertEquals("Rejected", h.entry.history.single().failureMessage)
        assertTrue(h.mutations.isEmpty())
    }

    @Test fun terminalLocalCommitInterruptionKeepsSavedProofAndRestartsWithoutFinancialSubmission() = runBlocking {
        val h = terminalHarness()
        val f = h.f
        val signed = requireNotNull(h.entry.pending)
        val projection = f.project(signed)
        f.transaction = projection
        h.exposeOutputs(projection)
        h.persistFailure = { if (it.channels.getValue(signed.keytag.value).state is ChannelState.FundsReturned) error("local interrupted") }
        assertFails { h.reopen().reconcile(f.profile.id, signed.keytag) }
        assertEquals(ChannelState.Ending, h.entry.state)
        assertNotNull(h.entry.returnProof)
        assertNotNull(h.entry.pending)
        h.persistFailure = {}
        h.reopen().reconcileAll(f.profile.id)
        assertIs<ChannelState.FundsReturned>(h.entry.state)
        assertEquals(1, f.signingCalls)
        assertTrue(h.mutations.isEmpty())
    }

    @Test fun settlementDriveFailureNeverDiscardsOriginalRecoveryBytes() = runBlocking {
        val h = terminalHarness()
        val f = h.f
        val signed = requireNotNull(h.entry.pending)
        val projection = f.project(signed)
        f.transaction = projection
        h.exposeOutputs(projection)
        h.reopen().reconcileAll(f.profile.id)
        f.transaction = projection.copy(depth = 2_160)
        h.commitFailure = { error("Drive unavailable during settlement") }
        assertFails { h.reopen().reconcileAll(f.profile.id) }
        assertEquals(signed.operationId, h.entry.confirmedReturnOperation?.operationId)
        h.commitFailure = {}
        h.reopen().reconcileAll(f.profile.id)
        assertNull(h.entry.confirmedReturnOperation)
        assertTrue(h.mutations.isEmpty())
        assertEquals(1, f.signingCalls)
    }

    @Test fun batchRefreshKeepsMissingLegacyChannelsLockedAndDoesNotRewriteUnchangedEvidence() = runBlocking {
        val f = CloseTransactionFixture()
        val observed = f.install(stage = ChannelDatumStage.Responded(0))
        val legacy = ChannelSnapshot(f.keytag("02"), f.usda, ChannelState.Closed,
            spendableBalance = AssetAmount(f.usda, 40))
        val h = Harness(f, observed, legacy)
        val repository = h.reopen()
        val reads = f.ledgerReads
        repository.reconcileAll(f.profile.id)
        assertEquals(reads + 1, f.ledgerReads)
        assertTrue(requireNotNull(h.entry.chainObservation).canReturn)
        assertEquals(legacy, h.stored.channels.getValue(legacy.keytag.value))
        val persisted = h.persisted
        val commits = h.commits
        repository.reconcileAll(f.profile.id)
        assertEquals(persisted, h.persisted)
        assertEquals(commits, h.commits)
        assertFails { repository.previewReturnFunds(f.profile.id, legacy.keytag) }
        assertEquals(0, f.signingCalls)
        assertTrue(h.mutations.isEmpty())
    }

    @Test fun submitRechecksSiblingAndLegacyGuardsAfterPreview() = runBlocking {
        for (legacy in listOf(false, true)) {
            val f = CloseTransactionFixture()
            val initial = f.install()
            val h = Harness(f, initial)
            val repository = h.reopen()
            val preview = repository.previewClose(f.profile.id, initial.keytag)
            h.stored = if (legacy) h.stored.copy(unresolvedLegacy = byteArrayOf(1)) else {
                val sibling = ChannelSnapshot(f.keytag("02"), f.catalog.ada, ChannelState.Open("sibling"),
                    pending = preview.operation.copy(keytag = f.keytag("02")))
                h.stored.copy(channels = h.stored.channels + (sibling.keytag.value to sibling))
            }
            val restarted = h.reopen()
            assertFails { restarted.submit(f.profile.id, preview) }
            assertFails { restarted.previewClose(f.profile.id, initial.keytag) }
            assertNull(h.entry.pending)
            assertEquals(initial.spendableBalance, h.entry.spendableBalance)
            assertEquals(0, f.signingCalls)
            assertTrue(h.mutations.isEmpty())
        }
    }

    @Test fun freshVaultJournalRestoresProofCommitAndRollbackWithoutFinancialReplay() = runBlocking {
        for (native in listOf(false, true)) {
            val f = CloseTransactionFixture()
            val initial = f.install(if (native) f.usda else f.catalog.ada,
                if (native) 40 else 3_000_000, ChannelDatumStage.Responded(0))
            val preview = f.transactions.previewReturnFunds(f.profile.id, initial, f.operationId)
            val signed = f.transactions.sign(f.profile.id, preview.operation)
            val h = Harness(f, initial.copy(state = ChannelState.Ending,
                pending = signed.copy(state = OperationState.SUBMITTED)))
            var disk = io.riverark.ferret.core.security.WalletEncryptedStateV1()
            fun freshJournal(): VaultChannelJournal {
                val vault = object : io.riverark.ferret.core.security.SecureVault by f.vault {
                    override suspend fun walletState(walletId: WalletId) = disk.copy(
                        operationJournal = disk.operationJournal.copyOf(), channelRecovery = disk.channelRecovery.copyOf())
                    override suspend fun updateWalletState(walletId: WalletId,
                        state: io.riverark.ferret.core.security.WalletEncryptedStateV1) {
                        disk = state.copy(operationJournal = state.operationJournal.copyOf(),
                            channelRecovery = state.channelRecovery.copyOf())
                    }
                }
                return VaultChannelJournal(vault, f.catalog)
            }
            freshJournal().persist(f.profile.id, h.stored)
            h.journalFactory = {
                val journal = freshJournal()
                object : ChannelJournal {
                    override suspend fun load(walletId: WalletId) = journal.load(walletId).also { h.stored = it }
                    override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) {
                        journal.persist(walletId, collection)
                        h.stored = journal.load(walletId)
                    }
                }
            }
            val exact = f.project(signed)
            f.transaction = exact
            h.exposeOutputs(exact)
            h.commitFailure = { error("Drive completion interrupted") }
            assertFails { h.reopen().reconcileAll(f.profile.id) }
            assertEquals(signed.operationId, freshJournal().load(f.profile.id).channels.getValue(signed.keytag.value)
                .confirmedReturnOperation?.operationId)
            h.commitFailure = {}
            h.reopen().reconcileAll(f.profile.id)
            assertIs<ChannelState.FundsReturned>(h.entry.state)
            f.transaction = exact.copy(depth = 4)
            h.reopen().reconcileAll(f.profile.id)
            assertEquals(ChannelState.Ending, h.entry.state)
            f.transaction = exact
            h.reopen().reconcileAll(f.profile.id)
            assertIs<ChannelState.FundsReturned>(h.entry.state)
            assertEquals(1, f.signingCalls)
            assertTrue(h.mutations.isEmpty())
            val restored = freshJournal().decodeBackup(f.profile.id, freshJournal().backupSnapshot(f.profile.id))
            assertEquals(h.entry.returnProof, restored.channels.getValue(signed.keytag.value).returnProof)
        }
    }

    private suspend fun terminalHarness(): Harness {
        val f = CloseTransactionFixture()
        val signed = f.signedOperation()
        return Harness(f, ChannelSnapshot(signed.keytag, signed.asset, ChannelState.Ending,
            pending = signed.copy(state = OperationState.SUBMITTED),
            spendableBalance = requireNotNull(signed.priorSpendableBalance)))
    }

    private class Harness(val f: CloseTransactionFixture, vararg entries: ChannelSnapshot) {
        private val selectedKey = entries.first().keytag.value
        var stored = ChannelCollectionV4(walletId = f.profile.id, catalogDigest = f.digest,
            channels = entries.associateBy { it.keytag.value })
        val entry get() = stored.channels.getValue(selectedKey)
        val mutations = mutableListOf<PreparedChannelOperation>()
        var lookups = 0
        var writerRequests = 0
        var writerFailure = false
        var writeFailure: (ChannelCollectionV4) -> Unit = {}
        var commitFailure: (ChannelCollectionV4) -> Unit = {}
        var persisted = 0
        var commits = 0
        var persistFailure: (ChannelCollectionV4) -> Unit = {}
        var mutateFailure: (PreparedChannelOperation) -> Unit = {}
        var lookup: (PreparedChannelOperation) -> ChannelRemoteResult? = { result(it, OperationState.SUBMITTED) }
        private var nextId = 20
        var journalFactory: (() -> ChannelJournal)? = null
        private val writer = WriterLease("e".repeat(64), 1, "f".repeat(64), "0".repeat(64), Long.MAX_VALUE)

        fun result(operation: PreparedChannelOperation, status: OperationState) = ChannelRemoteResult(
            operation.operationId, operation.intentHash, operation.keytag, operation.asset,
            transactionId = f.payload(operation).expectedTransactionId,
            state = if (operation.action == ChannelAction.Close) ChannelState.Closed else ChannelState.Absent,
            status = status, closeStep = f.intent(operation).step,
        )

        suspend fun reopen(enabled: Boolean = true): ChannelRepository = ChannelRepository(
            WalletRepository(),
            journalFactory?.invoke() ?: object : ChannelJournal {
                override suspend fun load(walletId: WalletId) = stored
                override suspend fun persist(walletId: WalletId, collection: ChannelCollectionV4) {
                    persistFailure(collection)
                    persisted++
                    stored = collection
                }
            },
            object : ChannelBackupProtocol {
                override suspend fun requireVerifiedWriter(walletId: WalletId): WriterLease {
                    writerRequests++
                    check(!writerFailure) { "stale writer" }
                    return writer
                }
                override suspend fun writeAhead(walletId: WalletId, collection: ChannelCollectionV4) = writeFailure(collection)
                override suspend fun commit(walletId: WalletId, collection: ChannelCollectionV4) {
                    commits++
                    commitFailure(collection)
                }
            },
            object : ChannelRemote {
                override suspend fun mutate(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease): ChannelRemoteResult {
                    mutations += operation
                    mutateFailure(operation)
                    return result(operation, OperationState.SUBMITTED)
                }
                override suspend fun reconcile(walletId: WalletId, operation: PreparedChannelOperation, writer: WriterLease): ChannelRemoteResult? {
                    lookups++
                    return lookup(operation)
                }
            },
            newOperationId = { "00000000-0000-4000-8000-${(nextId++).toString().padStart(12, '0')}" },
            transactions = f.transactions(enabled),
        ).also { it.load(f.profile.id) }

        fun exposeOutputs(transaction: ConnectorTransactionDto) {
            val outputs = transaction.outputs.mapIndexed { index, output ->
                LedgerUtxo(transaction.id, index, output.address,
                    Lovelace(output.value.single { it.unit == "lovelace" }.quantity.toLong()),
                    output.value.filter { it.unit != "lovelace" }.associate { it.unit to it.quantity.toLong() },
                    datumHex = output.datumInline,
                )
            }
            f.ledger = f.ledger.copy(utxos = f.ledger.utxos.filter { it.address != MAINNET.validatorAddress } +
                outputs.filter { it.address == MAINNET.validatorAddress })
        }
    }
}
