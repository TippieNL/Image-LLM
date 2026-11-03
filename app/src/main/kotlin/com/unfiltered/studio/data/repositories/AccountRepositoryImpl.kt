package com.unfiltered.studio.data.repositories

import com.unfiltered.studio.core.stringOrNull
import com.unfiltered.studio.data.network.huggingface.HfHubApi
import com.unfiltered.studio.data.network.huggingface.HfWhoAmIDto
import com.unfiltered.studio.data.providers.ProviderRegistryImpl
import com.unfiltered.studio.data.providers.retrofitCall
import com.unfiltered.studio.data.storage.SecureCredentialStore
import com.unfiltered.studio.domain.repositories.AccountRepository
import com.unfiltered.studio.domain.repositories.AccountsState
import com.unfiltered.studio.domain.repositories.HfAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepositoryImpl @Inject constructor(
    private val credentials: SecureCredentialStore,
    private val hub: HfHubApi,
    private val registry: ProviderRegistryImpl,
) : AccountRepository {

    private val hfAccount = MutableStateFlow<HfAccount?>(null)

    override val state: Flow<AccountsState> = combine(
        credentials.observeContains(SecureCredentialStore.HF_TOKEN),
        credentials.observeContains(SecureCredentialStore.CIVITAI_KEY),
        hfAccount,
    ) { hasHf, hasCivitai, account -> AccountsState(hasHf, account.takeIf { hasHf }, hasCivitai) }

    override suspend fun setHfToken(token: String?): HfAccount? {
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            credentials.remove(SecureCredentialStore.HF_TOKEN)
            hfAccount.value = null
            registry.invalidateAll()
            return null
        }
        // Validate before storing so a typo never replaces a working token.
        val account = whoAmI(trimmed)
        credentials.put(SecureCredentialStore.HF_TOKEN, trimmed)
        hfAccount.value = account
        registry.invalidateAll()
        return account
    }

    override suspend fun validateHfToken(): HfAccount {
        val token = credentials.get(SecureCredentialStore.HF_TOKEN)
            ?: throw com.unfiltered.studio.core.AppError.Configuration("No Hugging Face token saved.")
        return whoAmI(token).also { hfAccount.value = it }
    }

    override suspend fun setCivitaiKey(key: String?) {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) credentials.remove(SecureCredentialStore.CIVITAI_KEY)
        else credentials.put(SecureCredentialStore.CIVITAI_KEY, trimmed)
    }

    private suspend fun whoAmI(token: String): HfAccount {
        val dto = retrofitCall("Hugging Face", "huggingface.co", "Token check") { hub.whoAmI("Bearer $token") }
        return HfAccount(dto.name, dto.fullname, dto.auth?.accessToken?.displayName, canUseInference(dto))
    }

    /** Classic read/write tokens can call Inference Providers; fine-grained tokens need the explicit permission. */
    private fun canUseInference(dto: HfWhoAmIDto): Boolean? {
        val token = dto.auth?.accessToken ?: return null
        if (token.role != "fineGrained") return true
        val global = token.fineGrained?.get("global") as? JsonArray ?: return false
        return global.any { it.stringOrNull?.startsWith("inference.serverless") == true }
    }
}
