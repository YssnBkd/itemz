package com.jetbrains.receiptscanner.data.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Android implementation of PlaidService using Plaid API
 * This uses HTTP client to communicate with Plaid's REST API
 */
class PlaidServiceImpl(
    private val httpClient: HttpClient,
    private val clientId: String,
    private val secret: String,
    private val environment: String = "sandbox" // sandbox, development, or production
) : PlaidService {

    private val baseUrl = when (environment) {
        "production" -> "https://production.plaid.com"
        "development" -> "https://development.plaid.com"
        else -> "https://sandbox.plaid.com"
    }

    override suspend fun exchangePublicToken(publicToken: String): Result<PlaidAccountInfo> {
        return runCatching {
            val request = TokenExchangeRequest(
                client_id = clientId,
                secret = secret,
                public_token = publicToken
            )

            val response = httpClient.post("$baseUrl/link/token/exchange") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TokenExchangeResponse>()

            // Get account information
            val accountsResult = getAccounts(response.access_token).getOrThrow()

            PlaidAccountInfo(
                accessToken = response.access_token,
                itemId = response.item_id,
                accounts = accountsResult
            )
        }
    }

    override suspend fun fetchTransactions(
        accessToken: String,
        accountId: String,
        startDate: String?,
        endDate: String?
    ): Result<List<PlaidTransaction>> {
        return runCatching {
            val request = TransactionsGetRequest(
                client_id = clientId,
                secret = secret,
                access_token = accessToken,
                start_date = startDate ?: "2023-01-01",
                end_date = endDate ?: getCurrentDate(),
                account_ids = listOf(accountId)
            )

            val response = httpClient.post("$baseUrl/transactions/get") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<TransactionsGetResponse>()

            response.transactions.map { transaction ->
                PlaidTransaction(
                    transactionId = transaction.transaction_id,
                    accountId = transaction.account_id,
                    amount = transaction.amount,
                    date = transaction.date,
                    name = transaction.name,
                    merchantName = transaction.merchant_name,
                    category = transaction.category,
                    subcategory = transaction.category?.drop(1) // First item is main category
                )
            }
        }
    }

    override suspend fun getAccounts(accessToken: String): Result<List<PlaidAccount>> {
        return runCatching {
            val request = AccountsGetRequest(
                client_id = clientId,
                secret = secret,
                access_token = accessToken
            )

            val response = httpClient.post("$baseUrl/accounts/get") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body<AccountsGetResponse>()

            response.accounts.map { account ->
                PlaidAccount(
                    accountId = account.account_id,
                    name = account.name,
                    officialName = account.official_name,
                    type = account.type,
                    subtype = account.subtype,
                    mask = account.mask,
                    institutionId = null, // Would need separate call to get this
                    institutionName = null // Would need separate call to get this
                )
            }
        }
    }

    override suspend fun removeAccount(accessToken: String): Result<Unit> {
        return runCatching {
            val request = ItemRemoveRequest(
                client_id = clientId,
                secret = secret,
                access_token = accessToken
            )

            httpClient.post("$baseUrl/item/remove") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            Unit
        }
    }

    private fun getCurrentDate(): String {
        val now = kotlinx.datetime.Clock.System.now()
        return now.toString().substring(0, 10) // YYYY-MM-DD format
    }
}

// Plaid API request/response models
@Serializable
private data class TokenExchangeRequest(
    val client_id: String,
    val secret: String,
    val public_token: String
)

@Serializable
private data class TokenExchangeResponse(
    val access_token: String,
    val item_id: String,
    val request_id: String
)

@Serializable
private data class AccountsGetRequest(
    val client_id: String,
    val secret: String,
    val access_token: String
)

@Serializable
private data class AccountsGetResponse(
    val accounts: List<PlaidAccountResponse>,
    val item: PlaidItemResponse,
    val request_id: String
)

@Serializable
private data class PlaidAccountResponse(
    val account_id: String,
    val name: String,
    val official_name: String?,
    val type: String,
    val subtype: String?,
    val mask: String?
)

@Serializable
private data class PlaidItemResponse(
    val item_id: String,
    val institution_id: String?,
    val webhook: String?,
    val error: String?
)

@Serializable
private data class TransactionsGetRequest(
    val client_id: String,
    val secret: String,
    val access_token: String,
    val start_date: String,
    val end_date: String,
    val account_ids: List<String>? = null,
    val count: Int = 100,
    val offset: Int = 0
)

@Serializable
private data class TransactionsGetResponse(
    val accounts: List<PlaidAccountResponse>,
    val transactions: List<PlaidTransactionResponse>,
    val total_transactions: Int,
    val request_id: String
)

@Serializable
private data class PlaidTransactionResponse(
    val transaction_id: String,
    val account_id: String,
    val amount: Double,
    val date: String,
    val name: String,
    val merchant_name: String?,
    val category: List<String>?
)

@Serializable
private data class ItemRemoveRequest(
    val client_id: String,
    val secret: String,
    val access_token: String
)
