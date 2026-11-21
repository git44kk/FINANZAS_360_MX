package mx.finanzas360.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceMathTest {
    @Test
    fun savingsRateIsCalculatedFromIncomeAndExpense() {
        assertEquals(20.0, FinanceInsights.savingsRate(10000.0, 8000.0), 0.001)
    }

    @Test
    fun projectedExpenseUsesElapsedDays() {
        assertEquals(3000.0, FinanceInsights.projectedExpense(1000.0, 10, 30), 0.001)
    }

    @Test
    fun budgetPercentIsClamped() {
        assertEquals(1.0f, FinanceInsights.budgetPercent(120.0, 100.0), 0.001f)
    }
}


class BalanceCalculatorTest {
    @Test
    fun checkingAccountAppliesIncomeExpenseAndTransfers() {
        val events = listOf(
            BalanceEvent("EXPENSE", 200.0, true, false),
            BalanceEvent("INCOME", 500.0, true, false),
            BalanceEvent("TRANSFER", 300.0, true, false),
            BalanceEvent("TRANSFER", 100.0, false, true)
        )
        assertEquals(1100.0, BalanceCalculator.calculate(1000.0, "CHECKING", events), 0.001)
    }

    @Test
    fun creditCardPurchasesIncreaseDebtAndPaymentsReduceIt() {
        val events = listOf(
            BalanceEvent("EXPENSE", 200.0, true, false),
            BalanceEvent("TRANSFER", 500.0, false, true),
            BalanceEvent("TRANSFER", 100.0, true, false)
        )
        assertEquals(800.0, BalanceCalculator.calculate(1000.0, "CREDIT_CARD", events), 0.001)
    }

    @Test
    fun transferIsNotAnIncomeOrExpense() {
        val events = listOf(BalanceEvent("TRANSFER", 750.0, true, false))
        assertEquals(250.0, BalanceCalculator.calculate(1000.0, "CHECKING", events), 0.001)
    }
}

class InvestmentCalculatorTest {
    @Test
    fun dailyCompoundProducesValueAbovePrincipal() {
        val result = InvestmentCalculator.calculate(
            listOf(InvestmentCashflow("2025-01-01", 1000.0)),
            annualRatePercent = 10.0,
            asOf = java.time.LocalDate.parse("2026-01-01")
        )
        assertEquals(1000.0, result.principal, 0.001)
        val expected = 1000.0 * Math.pow(1.0 + 0.10 / 365.0, 365.0)
        assertEquals(expected, result.currentValue, 0.1)
    }

    @Test
    fun withdrawalsReducePrincipalAndContinueCompounding() {
        val result = InvestmentCalculator.calculate(
            listOf(
                InvestmentCashflow("2025-01-01", 1000.0),
                InvestmentCashflow("2025-07-01", -500.0)
            ),
            annualRatePercent = 8.0,
            asOf = java.time.LocalDate.parse("2026-01-01")
        )
        assertEquals(500.0, result.principal, 0.001)
        assertTrue(result.currentValue > 500.0)
        assertTrue(result.accruedInterest > 0.0)
    }
}

class CashFlowMathTest {
    @Test
    fun closingBalanceUsesOpeningPlusIncomeLessExpense() {
        assertEquals(12500.0, CashFlowMath.closing(10000.0, 5000.0, 2500.0), 0.001)
    }
}


class InvestmentYieldOverrideTest {
    private val janEnd = java.time.LocalDate.parse("2025-01-31")
    private val principal = listOf(InvestmentCashflow("2025-01-01", 1000.0))

    @Test
    fun noManualEntryKeepsAutomaticDailyCalculation() {
        val automatic = InvestmentCalculator.calculate(principal, 10.0, janEnd)
        val withEmptyOverrides = InvestmentCalculator.calculateWithOverrides(principal, 10.0, emptyList(), janEnd)
        assertEquals(automatic.currentValue, withEmptyOverrides.currentValue, 0.001)
        assertEquals(automatic.principal, withEmptyOverrides.principal, 0.001)
    }

    @Test
    fun monthlyManualYieldReplacesAutomaticInterestInsteadOfAddingToIt() {
        val result = InvestmentCalculator.calculateWithOverrides(
            principal,
            10.0,
            listOf(InvestmentYieldEntry("2025-01-31", 25.50, "MONTHLY")),
            janEnd
        )
        assertEquals(1000.0, result.principal, 0.001)
        assertEquals(1025.50, result.currentValue, 0.001)
    }

    @Test
    fun adjustedYieldReplacesTheAutomaticEstimate() {
        val result = InvestmentCalculator.calculateWithOverrides(
            principal,
            10.0,
            listOf(InvestmentYieldEntry("2025-01-31", 31.25, "ADJUSTED")),
            janEnd
        )
        assertEquals(1031.25, result.currentValue, 0.001)
    }

    @Test
    fun monthlyEntryResumesAutomaticCompoundingAfterItsRecognitionDate() {
        val date = java.time.LocalDate.parse("2025-01-15")
        val result = InvestmentCalculator.calculateWithOverrides(
            principal,
            10.0,
            listOf(InvestmentYieldEntry(date.toString(), 25.0, "MONTHLY")),
            janEnd
        )
        val expected = 1025.0 * Math.pow(1.0 + 0.10 / 365.0, 16.0)
        assertEquals(expected, result.currentValue, 0.01)
    }

    @Test
    fun dailyManualYieldsReplaceAutomaticAccrualForThatMonth() {
        val result = InvestmentCalculator.calculateWithOverrides(
            principal,
            10.0,
            listOf(
                InvestmentYieldEntry("2025-01-02", 1.10, "DAILY"),
                InvestmentYieldEntry("2025-01-03", 1.11, "DAILY"),
                InvestmentYieldEntry("2025-01-31", 1.12, "DAILY")
            ),
            janEnd
        )
        assertEquals(1000.0, result.principal, 0.001)
        assertEquals(1003.33, result.currentValue, 0.001)
    }
}
