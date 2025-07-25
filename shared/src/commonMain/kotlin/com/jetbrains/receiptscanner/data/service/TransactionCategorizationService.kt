package com.jetbrains.receiptscanner.data.service

import com.jetbrains.receiptscanner.domain.model.BankTransaction

/**
 * Service for categorizing bank transactions to identify grocery-related purchases
 * Uses multiple heuristics including merchant names, categories, and transaction patterns
 */
class TransactionCategorizationService {

    private val groceryMerchants = setOf(
        // Major grocery chains
        "KROGER", "WALMART", "SAFEWAY", "PUBLIX", "WHOLE FOODS", "TARGET",
        "COSTCO", "SAM'S CLUB", "TRADER JOE", "ALDI", "FOOD LION",
        "HARRIS TEETER", "WEGMANS", "GIANT", "STOP & SHOP", "KING SOOPERS",
        "RALPHS", "VONS", "ALBERTSONS", "MEIJER", "H-E-B", "WINCO",
        "FRESH MARKET", "PIGGLY WIGGLY", "BI-LO", "WINN-DIXIE",

        // Regional chains
        "SHOPRITE", "ACME", "FOOD BASICS", "PRICE CHOPPER", "HANNAFORD",
        "MARKET BASKET", "BIG Y", "CARALUZZI", "IGA", "BROOKSHIRE",
        "UNITED SUPERMARKETS", "MARKET STREET", "AMIGOS", "LOWES FOODS",
        "INGLES", "FOOD CITY", "SCHNUCKS", "DIERBERGS", "HY-VEE",
        "FAREWAY", "CASH WISE", "HORNBACHER'S", "HUGO'S", "FAMILY FARE",
        "D&W FRESH MARKET", "FESTIVAL FOODS", "PICK 'N SAVE", "METRO MARKET",
        "COPPS", "WOODMAN'S", "PIGGLY WIGGLY", "SENTRY", "FRESH THYME",

        // Warehouse clubs
        "BJ'S", "COSTCO WHOLESALE", "SAM'S CLUB",

        // Natural/Organic
        "SPROUTS", "NATURAL GROCERS", "EARTH FARE", "MOM'S ORGANIC",

        // International/Ethnic
        "H MART", "99 RANCH", "MITSUWA", "MARUKAI", "NIJIYA",
        "LOTTE PLAZA", "ZION MARKET", "GALLERIA MARKET", "FRESH & EASY"
    )

    private val groceryKeywords = setOf(
        "GROCERY", "SUPERMARKET", "MARKET", "FOOD", "FRESH", "ORGANIC",
        "PRODUCE", "DELI", "BAKERY", "BUTCHER", "MEAT", "SEAFOOD"
    )

    private val groceryCategories = setOf(
        "Food and Drink", "Groceries", "Supermarkets", "Food & Dining",
        "Grocery Stores", "Food Stores", "Convenience Stores"
    )

    /**
     * Categorize a list of transactions to identify grocery-related purchases
     */
    fun categorizeTransactions(transactions: List<BankTransaction>): List<BankTransaction> {
        return transactions.map { transaction ->
            transaction.copy(
                isGroceryRelated = isGroceryTransaction(transaction)
            )
        }
    }

    /**
     * Determine if a single transaction is grocery-related
     */
    fun isGroceryTransaction(transaction: BankTransaction): Boolean {
        val merchantName = transaction.merchantName.uppercase().trim()

        // Check exact merchant matches
        if (groceryMerchants.any { merchant -> merchantName.contains(merchant) }) {
            return true
        }

        // Check keyword matches
        if (groceryKeywords.any { keyword -> merchantName.contains(keyword) }) {
            return true
        }

        // Additional heuristics for edge cases
        return isLikelyGroceryStore(merchantName, transaction.amount)
    }

    /**
     * Additional heuristics for identifying grocery stores
     */
    private fun isLikelyGroceryStore(merchantName: String, amount: Double): Boolean {
        // Common patterns in grocery store names
        val groceryPatterns = listOf(
            Regex(".*FOOD.*MART.*"),
            Regex(".*SUPER.*CENTER.*"),
            Regex(".*FRESH.*MARKET.*"),
            Regex(".*CORNER.*STORE.*"),
            Regex(".*MINI.*MART.*"),
            Regex(".*QUICK.*STOP.*"),
            Regex(".*FAMILY.*DOLLAR.*"), // Often sells groceries
            Regex(".*DOLLAR.*GENERAL.*"), // Often sells groceries
            Regex(".*7-ELEVEN.*"),
            Regex(".*CIRCLE.*K.*"),
            Regex(".*WAWA.*"),
            Regex(".*SHEETZ.*"),
            Regex(".*SPEEDWAY.*"),
            Regex(".*MARATHON.*"),
            Regex(".*BP.*"), // Gas stations often sell groceries
            Regex(".*SHELL.*"),
            Regex(".*EXXON.*"),
            Regex(".*CHEVRON.*")
        )

        val matchesPattern = groceryPatterns.any { pattern ->
            pattern.matches(merchantName)
        }

        // If it matches a pattern and the amount is reasonable for groceries
        if (matchesPattern && amount > 5.0 && amount < 500.0) {
            return true
        }

        // Check for common grocery store suffixes
        val grocerySuffixes = listOf("FOODS", "MARKET", "MART", "GROCERY", "SUPERMARKET")
        if (grocerySuffixes.any { suffix -> merchantName.endsWith(suffix) }) {
            return true
        }

        return false
    }

    /**
     * Get confidence score for grocery categorization (0.0 to 1.0)
     */
    fun getCategorizationConfidence(transaction: BankTransaction): Double {
        val merchantName = transaction.merchantName.uppercase().trim()

        // High confidence for exact matches
        if (groceryMerchants.any { merchant -> merchantName.contains(merchant) }) {
            return 0.95
        }

        // Medium-high confidence for keyword matches
        if (groceryKeywords.any { keyword -> merchantName.contains(keyword) }) {
            return 0.80
        }

        // Medium confidence for pattern matches
        if (isLikelyGroceryStore(merchantName, transaction.amount)) {
            return 0.65
        }

        // Low confidence - not identified as grocery
        return 0.10
    }

    /**
     * Suggest merchant name corrections for better categorization
     */
    fun suggestMerchantCorrections(merchantName: String): List<String> {
        val suggestions = mutableListOf<String>()
        val upperMerchant = merchantName.uppercase()

        // Find close matches in known grocery merchants
        groceryMerchants.forEach { knownMerchant ->
            if (upperMerchant.contains(knownMerchant.substring(0, minOf(4, knownMerchant.length)))) {
                suggestions.add(knownMerchant)
            }
        }

        return suggestions.take(3) // Return top 3 suggestions
    }
}
