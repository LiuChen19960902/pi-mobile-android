package com.finecat.pimobile.android.ui.finecat

import com.finecat.pimobile.android.ProviderTestAttempt
import com.finecat.pimobile.android.ui.ProviderModelDraft
import com.finecat.pimobile.shared.protocol.GatewayProvider
import com.finecat.pimobile.shared.protocol.GatewayProviderModel
import org.junit.Assert.*
import org.junit.Test

class ProviderSavePolicyTest {
    private val initial = GatewayProvider("p", baseUrl = "https://example.invalid/v1", models = listOf(
        GatewayProviderModel("a", api = "openai-completions"),
        GatewayProviderModel("b", api = "anthropic-messages", baseUrl = "https://other.invalid"),
    ))
    private val rows = FineCatProviderForm.initialRows(initial)

    private fun groups(drafts: List<ProviderModelDraft> = rows, url: String = initial.baseUrl!!, key: String = "") =
        FineCatProviderForm.requiredTestGroups(initial, "p", url, key, drafts)

    private fun connection(row: ProviderModelDraft, url: String = initial.baseUrl!!, key: String = "") =
        FineCatProviderForm.connectionFingerprint("p", url, key, row)

    @Test fun metadataSavesWithoutAnyPastSuccessRecord() {
        assertNull(initial.status)
        listOf(
            rows[0].copy(name = "New name"), rows[0].copy(contextWindow = "500K"),
            rows[0].copy(maxTokens = "275K"), rows[0].copy(reasoning = false, imageOn = true),
            rows[0].copy(thinkingMap = mapOf("low" to "none")),
            rows[0].copy(compat = mapOf("supportsStore" to "false"), compatTouched = true),
        ).forEach { changed -> assertTrue(groups(listOf(changed, rows[1])).isEmpty()) }
    }

    @Test fun deletionAndReorderNeverRequireRetesting() {
        assertTrue(groups(rows.reversed()).isEmpty())
        assertTrue(groups(rows.drop(1)).isEmpty())
        assertTrue(groups(emptyList()).isEmpty())
        assertTrue(groups(emptyList(), url = "https://new.invalid/v1", key = "replacement").isEmpty())
    }

    @Test fun newProviderNeedsOneSuccessfulModelButEmptyProviderCannotPass() {
        val required = FineCatProviderForm.requiredTestGroups(null, "p", initial.baseUrl!!, "key", rows)
        assertEquals(listOf(rows), required)
        val success = mapOf(connection(rows[1], key = "key") to ProviderTestAttempt("b", true, "ok"))
        assertTrue(FineCatProviderForm.untestedGroups(required, "p", initial.baseUrl!!, "key", success).isEmpty())
        val empty = FineCatProviderForm.requiredTestGroups(null, "p", initial.baseUrl!!, "key", emptyList())
        assertEquals(1, FineCatProviderForm.untestedGroups(empty, "p", initial.baseUrl!!, "key", success).size)
    }

    @Test fun modelChangesCannotBorrowAnotherModelsSuccess() {
        val changed = rows[0].copy(api = "openai-responses")
        val required = groups(listOf(changed, rows[1]))
        assertEquals(listOf(listOf(changed)), required)
        val unrelated = mapOf(connection(rows[1]) to ProviderTestAttempt("b", true, "ok"))
        assertEquals(required, FineCatProviderForm.untestedGroups(required, "p", initial.baseUrl!!, "", unrelated))
        val exact = unrelated + (connection(changed) to ProviderTestAttempt("a", true, "ok"))
        assertTrue(FineCatProviderForm.untestedGroups(required, "p", initial.baseUrl!!, "", exact).isEmpty())
    }

    @Test fun addedOrRenamedModelAndUrlChangeEachNeedTheirOwnTest() {
        val added = ProviderModelDraft(id = "new")
        val moved = rows[0].copy(baseUrl = "https://moved.invalid/v1", baseUrlTouched = true)
        assertEquals(listOf(listOf(added)), groups(rows + added))
        assertEquals(listOf(listOf(moved)), groups(listOf(moved, rows[1])))
        assertEquals(listOf(listOf(rows[0].copy(id = "renamed"))), groups(listOf(rows[0].copy(id = "renamed"), rows[1])))
    }

    @Test fun sharedUrlTestsOnlyModelsThatActuallyInheritIt() {
        assertEquals(listOf(listOf(rows[0])), groups(url = "https://new.invalid/v1"))
        assertTrue(groups(rows.drop(1), url = "https://new.invalid/v1").isEmpty())
        assertEquals(listOf(rows), groups(key = "replacement"))
    }

    @Test fun equivalentEffectiveUrlAndBlankPreserveDoNotNeedTests() {
        val same = rows[0].copy(baseUrl = initial.baseUrl!!, baseUrlTouched = true)
        assertTrue(groups(listOf(same, rows[1])).isEmpty())
        assertTrue(groups(url = "   ").isEmpty())
        assertEquals(connection(rows[0]), connection(same))
    }

    @Test fun lateResultDoesNotCertifyEditedConnectionOrProvider() {
        val changed = rows[0].copy(api = "openai-responses")
        val required = groups(listOf(changed, rows[1]))
        val before = mapOf(connection(rows[0]) to ProviderTestAttempt("a", true, "ok"))
        assertFalse(FineCatProviderForm.untestedGroups(required, "p", initial.baseUrl!!, "", before).isEmpty())
        assertNotEquals(connection(rows[0]), connection(rows[0], key = "replacement"))
        assertNotEquals(connection(rows[0]), FineCatProviderForm.connectionFingerprint("other", initial.baseUrl!!, "", rows[0]))
    }

    @Test fun deletingOtherModelDoesNotInvalidateAnExactSuccessfulTest() {
        val changed = rows[0].copy(api = "openai-responses")
        val passed = mapOf(connection(changed) to ProviderTestAttempt("a", true, "ok"))
        assertTrue(FineCatProviderForm.untestedGroups(groups(listOf(changed)), "p", initial.baseUrl!!, "", passed).isEmpty())
        val failed = mapOf(connection(changed) to ProviderTestAttempt("a", false, "timeout"))
        assertFalse(FineCatProviderForm.untestedGroups(groups(listOf(changed)), "p", initial.baseUrl!!, "", failed).isEmpty())
    }

    @Test fun multipleChangedModelsEachRemainRequired() {
        val changed = rows.map { it.copy(api = "openai-responses") }
        assertEquals(changed.map { listOf(it) }, groups(changed))
        val firstOnly = mapOf(connection(changed[0]) to ProviderTestAttempt("a", true, "ok"))
        assertEquals(listOf(listOf(changed[1])), FineCatProviderForm.untestedGroups(groups(changed), "p", initial.baseUrl!!, "", firstOnly))
    }
}
