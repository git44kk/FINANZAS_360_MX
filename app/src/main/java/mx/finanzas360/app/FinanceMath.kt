package mx.finanzas360.app

import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale
import kotlin.math.pow

object MoneyFormat {
    fun format(value: Double, currencyCode: String): String = try {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX")).apply {
            currency = Currency.getInstance(currencyCode)
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }.format(value)
    } catch (_: Exception) { "$currencyCode ${"%.2f".format(value)}" }
}

object FinanceInsights {
    fun savingsRate(income: Double, expense: Double): Double =
        if (income <= 0.0) 0.0 else (income - expense) / income * 100.0

    fun projectedExpense(currentExpense: Double, elapsedDays: Int, daysInMonth: Int): Double {
        if (elapsedDays <= 0) return 0.0
        return currentExpense / elapsedDays.coerceAtMost(daysInMonth) * daysInMonth
    }

    fun budgetPercent(actual: Double, budget: Double): Float =
        if (budget <= 0.0) 0f else (actual / budget).toFloat().coerceIn(0f, 1f)

    fun dailyRate(annualRatePercent: Double): Double = annualRatePercent.coerceAtLeast(0.0) / 100.0 / 365.0
}

data class BalanceEvent(
    val kind: String,
    val amount: Double,
    val isSourceAccount: Boolean,
    val isTargetAccount: Boolean
)

object BalanceCalculator {
    fun calculate(openingBalance: Double, accountType: String, events: List<BalanceEvent>): Double {
        val liability = accountType == "CREDIT_CARD" || accountType == "LOAN"
        var balance = openingBalance
        events.forEach { event ->
            when (event.kind) {
                "INCOME" -> if (event.isSourceAccount) balance += if (liability) -event.amount else event.amount
                "EXPENSE" -> if (event.isSourceAccount) balance += if (liability) event.amount else -event.amount
                "TRANSFER" -> {
                    if (event.isSourceAccount) balance += if (liability) event.amount else -event.amount
                    if (event.isTargetAccount) balance += if (liability) -event.amount else event.amount
                }
            }
        }
        return balance
    }
}

/** Daily compound-interest estimate. Cash flows dated on a day enter before that day's accrual. */
object InvestmentCalculator {
    fun calculate(
        flows: List<InvestmentCashflow>,
        annualRatePercent: Double,
        asOf: LocalDate = LocalDate.now()
    ): InvestmentResult {
        val ordered = flows.mapNotNull { flow ->
            runCatching { LocalDate.parse(flow.date) }.getOrNull()?.let { it to flow.amount }
        }.filter { !it.first.isAfter(asOf) }.sortedBy { it.first }
        var value = 0.0
        var principal = 0.0
        var lastDate: LocalDate? = null
        val dailyFactor = 1.0 + FinanceInsights.dailyRate(annualRatePercent)
        ordered.forEach { (date, amount) ->
            val last = lastDate
            if (last != null) {
                val days = ChronoUnit.DAYS.between(last, date).coerceAtLeast(0)
                value *= dailyFactor.pow(days.toDouble())
            }
            value += amount
            principal += amount
            lastDate = date
        }
        val last = lastDate
        if (last != null && asOf.isAfter(last)) {
            val days = ChronoUnit.DAYS.between(last, asOf).coerceAtLeast(0)
            value *= dailyFactor.pow(days.toDouble())
        }
        return InvestmentResult(principal, value)
    }

    fun project(principal: Double, annualRatePercent: Double, days: Int): Double {
        val factor = 1.0 + FinanceInsights.dailyRate(annualRatePercent)
        return principal * factor.pow(days.coerceAtLeast(0).toDouble())
    }

    /**
     * Daily compound estimate with actual yield overrides.
     * A month with DAILY entries or one MONTHLY/ADJUSTED entry does not also
     * receive automatic accrual; this prevents counting the bank's yield twice.
     */
    fun calculateWithOverrides(
        flows: List<InvestmentCashflow>,
        annualRatePercent: Double,
        yieldEntries: List<InvestmentYieldEntry>,
        asOf: LocalDate = LocalDate.now()
    ): InvestmentResult {
        val orderedFlows = flows.mapNotNull { flow ->
            runCatching { LocalDate.parse(flow.date) }.getOrNull()
                ?.takeIf { !it.isAfter(asOf) }?.let { it to flow.amount }
        }.sortedBy { it.first }

        if (orderedFlows.isEmpty()) return InvestmentResult(0.0, 0.0)

        val entries = yieldEntries.mapNotNull { entry ->
            runCatching { LocalDate.parse(entry.date) }.getOrNull()
                ?.takeIf { !it.isAfter(asOf) }?.let { it to entry }
        }
        val entriesByMonth = entries.groupBy { it.first.toString().take(7) }
        val flowsByDate = orderedFlows.groupBy { it.first }
        val entriesByDate = entries.groupBy { it.first }

        var value = 0.0
        var principal = 0.0
        var day = orderedFlows.first().first
        val dailyFactor = 1.0 + FinanceInsights.dailyRate(annualRatePercent)

        while (!day.isAfter(asOf)) {
            flowsByDate[day].orEmpty().forEach { (_, amount) ->
                value += amount
                principal += amount
            }

            val monthEntries = entriesByMonth[day.toString().take(7)].orEmpty().map { it.second }
            val monthlyOverride = monthEntries.firstOrNull {
                it.type == "MONTHLY" || it.type == "ADJUSTED"
            }
            when {
                monthlyOverride != null -> {
                    if (day == runCatching { LocalDate.parse(monthlyOverride.date) }.getOrNull()) {
                        value += monthlyOverride.amount
                    }
                    val overrideDate = runCatching { LocalDate.parse(monthlyOverride.date) }.getOrNull()
                    if (overrideDate != null && !day.isBefore(overrideDate) && day.isBefore(asOf)) {
                        value *= dailyFactor
                    }
                }
                monthEntries.any { it.type == "DAILY" } -> {
                    entriesByDate[day].orEmpty()
                        .filter { it.second.type == "DAILY" }
                        .forEach { value += it.second.amount }
                }
                else -> if (day.isBefore(asOf)) value *= dailyFactor
            }
            day = day.plusDays(1)
        }
        return InvestmentResult(principal, value)
    }
}

object CashFlowMath {
    fun closing(opening: Double, income: Double, expense: Double): Double = opening + income - expense
}
