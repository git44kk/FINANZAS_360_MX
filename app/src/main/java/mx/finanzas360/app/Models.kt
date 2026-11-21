package mx.finanzas360.app

data class Account(
    val id: Long,
    val institution: String,
    val name: String,
    val type: String,
    val currency: String,
    val initialBalance: Double,
    val balance: Double,
    val creditLimit: Double,
    val cutoffDay: Int,
    val dueDay: Int,
    val annualRate: Double
)

data class Category(val id: Long, val name: String, val type: String, val parentId: Long?)

data class TxItem(
    val id: Long, val kind: String, val amount: Double, val currency: String,
    val categoryName: String, val accountName: String, val transferAccountName: String,
    val paymentMethod: String, val description: String, val date: String
)

data class BudgetSummary(
    val categoryId: Long, val categoryName: String, val categoryType: String,
    val budget: Double, val actual: Double, val currency: String
)

data class ReminderItem(
    val id: Long, val title: String, val amount: Double, val currency: String,
    val dueAt: Long, val repeatRule: String, val accountId: Long?, val done: Boolean
)

data class MonthlyTotals(val income: Double, val expense: Double) {
    val savings: Double get() = income - expense
}

data class CurrencyMonthlyTotals(val currency: String, val income: Double, val expense: Double) {
    val savings: Double get() = income - expense
}

data class CurrencyNetWorth(val currency: String, val assets: Double, val liabilities: Double) {
    val netWorth: Double get() = assets - liabilities
}

data class CreditStatement(
    val id: Long,
    val accountId: Long,
    val accountName: String,
    val currency: String,
    val period: String,
    val closingDate: String,
    val dueDate: String,
    val statementBalance: Double,
    val minimumPayment: Double,
    val paidAmount: Double,
    val paidByDueDate: Double
) {
    val remaining: Double get() = (statementBalance - paidAmount).coerceAtLeast(0.0)
    val isPaid: Boolean get() = remaining <= 0.009
    val isPaidOnTime: Boolean get() = paidByDueDate + 0.009 >= statementBalance
}

data class InvestmentSummary(
    val accountId: Long,
    val name: String,
    val currency: String,
    val principal: Double,
    val currentValue: Double,
    val accruedInterest: Double,
    val annualRate: Double
)

data class NetWorthPoint(val date: String, val assets: Double, val liabilities: Double) {
    val netWorth: Double get() = assets - liabilities
}

data class CashFlowPlan(
    val id: Long,
    val title: String,
    val kind: String,
    val amount: Double,
    val currency: String,
    val nextDate: String,
    val repeatRule: String,
    val active: Boolean
)

data class CashFlowProjection(
    val month: String,
    val currency: String,
    val openingBalance: Double,
    val expectedIncome: Double,
    val expectedExpense: Double,
    val closingBalance: Double
)

data class InvestmentCashflow(val date: String, val amount: Double)
data class InvestmentResult(val principal: Double, val currentValue: Double) {
    val accruedInterest: Double get() = currentValue - principal
}


data class InvestmentYieldEntry(
    val date: String,
    val amount: Double,
    val type: String
)

data class InvestmentYieldMonthSummary(
    val accountId: Long,
    val month: String,
    val currency: String,
    val estimatedAmount: Double,
    val effectiveAmount: Double,
    val mode: String,
    val entryCount: Int,
    val recordedAmount: Double
)


data class InvestmentYieldEntryRecord(
    val id: Long,
    val date: String,
    val amount: Double,
    val type: String,
    val note: String
)
