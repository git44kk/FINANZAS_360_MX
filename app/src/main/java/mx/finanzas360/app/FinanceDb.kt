package mx.finanzas360.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

class FinanceDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "finanzas360.db", DatabaseKeyStore.getOrCreatePassphrase(context), null, 3, 0, null, null, true) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Configure foreign keys through the database API.
        // Do NOT invoke execSQL() on PRAGMAs that may return rows: SQLCipher's
        // non-query executor throws SQLiteException (code 0) in that case.
        // The database remains encrypted by SQLCipher; these two optional
        // PRAGMA calls are deliberately omitted during startup.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE institutions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )"""
        )
        db.execSQL(
            """CREATE TABLE accounts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                institution TEXT NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                currency TEXT NOT NULL DEFAULT 'MXN',
                initial_balance REAL NOT NULL DEFAULT 0,
                credit_limit REAL NOT NULL DEFAULT 0,
                cutoff_day INTEGER NOT NULL DEFAULT 0,
                due_day INTEGER NOT NULL DEFAULT 0,
                annual_rate REAL NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1
            )"""
        )
        db.execSQL(
            """CREATE TABLE categories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                parent_id INTEGER,
                color TEXT NOT NULL DEFAULT '#7667E8',
                UNIQUE(name, type, parent_id),
                FOREIGN KEY(parent_id) REFERENCES categories(id) ON DELETE SET NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE transactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                amount REAL NOT NULL,
                currency TEXT NOT NULL,
                category_id INTEGER,
                account_id INTEGER NOT NULL,
                transfer_account_id INTEGER,
                payment_method TEXT NOT NULL DEFAULT 'OTHER',
                description TEXT NOT NULL DEFAULT '',
                date_iso TEXT NOT NULL,
                receipt_uri TEXT,
                import_key TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE SET NULL,
                FOREIGN KEY(account_id) REFERENCES accounts(id),
                FOREIGN KEY(transfer_account_id) REFERENCES accounts(id)
            )"""
        )
        db.execSQL("CREATE INDEX idx_tx_date ON transactions(date_iso)")
        db.execSQL("CREATE INDEX idx_tx_account ON transactions(account_id, transfer_account_id)")
        db.execSQL("CREATE INDEX idx_tx_category ON transactions(category_id)")
        db.execSQL(
            """CREATE TABLE budgets(
                month TEXT NOT NULL,
                category_id INTEGER NOT NULL,
                amount REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'MXN',
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(month, category_id, currency),
                FOREIGN KEY(category_id) REFERENCES categories(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL(
            """CREATE TABLE reminders(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                amount REAL NOT NULL DEFAULT 0,
                currency TEXT NOT NULL DEFAULT 'MXN',
                due_at INTEGER NOT NULL,
                repeat_rule TEXT NOT NULL DEFAULT 'NONE',
                account_id INTEGER,
                done INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE budget_alerts(
                month TEXT NOT NULL,
                category_id INTEGER NOT NULL,
                currency TEXT NOT NULL,
                threshold INTEGER NOT NULL,
                sent_at INTEGER NOT NULL,
                PRIMARY KEY(month, category_id, currency, threshold)
            )"""
        )
        db.execSQL(
            """CREATE TABLE exchange_rates(
                base_currency TEXT NOT NULL,
                quote_currency TEXT NOT NULL,
                rate REAL NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(base_currency, quote_currency)
            )"""
        )
        db.execSQL(
            """CREATE TABLE goals(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                target_amount REAL NOT NULL,
                saved_amount REAL NOT NULL DEFAULT 0,
                currency TEXT NOT NULL DEFAULT 'MXN',
                target_date TEXT
            )"""
        )
        createAdvancedTables(db)
        seedCategories(db)
        db.execSQL("UPDATE sync_meta SET last_modified=0 WHERE id=1")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createAdvancedTables(db)
        if (oldVersion < 3) createInvestmentYieldTables(db)
    }

    private fun createAdvancedTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS card_statements(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL,
            period TEXT NOT NULL,
            closing_date TEXT NOT NULL,
            due_date TEXT NOT NULL,
            statement_balance REAL NOT NULL,
            minimum_payment REAL NOT NULL DEFAULT 0,
            reminder_id INTEGER,
            generated_at INTEGER NOT NULL,
            UNIQUE(account_id, period),
            FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS statement_payments(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            statement_id INTEGER NOT NULL,
            transaction_id INTEGER NOT NULL,
            amount REAL NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE(statement_id, transaction_id),
            FOREIGN KEY(statement_id) REFERENCES card_statements(id) ON DELETE CASCADE,
            FOREIGN KEY(transaction_id) REFERENCES transactions(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS net_worth_snapshots(
            snapshot_date TEXT NOT NULL,
            currency TEXT NOT NULL,
            assets REAL NOT NULL,
            liabilities REAL NOT NULL,
            PRIMARY KEY(snapshot_date, currency)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS cashflow_plans(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            kind TEXT NOT NULL,
            amount REAL NOT NULL,
            currency TEXT NOT NULL,
            next_date TEXT NOT NULL,
            repeat_rule TEXT NOT NULL DEFAULT 'NONE',
            active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS sync_meta(
            id INTEGER PRIMARY KEY CHECK(id=1),
            last_modified INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("INSERT OR IGNORE INTO sync_meta(id,last_modified) VALUES(1,0)")
        createInvestmentYieldTables(db)
        val tables = listOf("accounts", "categories", "transactions", "budgets", "budget_alerts", "reminders", "goals", "exchange_rates", "card_statements", "statement_payments", "cashflow_plans", "investment_yield_entries")
        tables.forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val suffix = operation.lowercase()
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS trg_sync_${table}_$suffix
                    AFTER $operation ON $table BEGIN
                    UPDATE sync_meta SET last_modified=MAX(last_modified+1,CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER)) WHERE id=1;
                    END""")
            }
        }
    }

    private fun createInvestmentYieldTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS investment_yield_entries(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account_id INTEGER NOT NULL,
            period_month TEXT NOT NULL,
            entry_date TEXT NOT NULL,
            entry_type TEXT NOT NULL CHECK(entry_type IN ('DAILY','MONTHLY','ADJUSTED')),
            amount REAL NOT NULL CHECK(amount >= 0),
            note TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS idx_inv_yield_daily_unique
            ON investment_yield_entries(account_id, entry_date) WHERE entry_type='DAILY'""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS idx_inv_yield_monthly_unique
            ON investment_yield_entries(account_id, period_month)
            WHERE entry_type IN ('MONTHLY','ADJUSTED')""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inv_yield_month ON investment_yield_entries(account_id, period_month)")
        listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
            val suffix = operation.lowercase()
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS trg_sync_investment_yield_entries_$suffix
                AFTER $operation ON investment_yield_entries BEGIN
                UPDATE sync_meta SET last_modified=MAX(last_modified+1,CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER)) WHERE id=1;
                END""")
        }
    }

    private fun seedCategories(db: SQLiteDatabase) {
        val defaults = listOf(
            "EXPENSE" to listOf("Alimentación", "Transporte", "Hogar", "Servicios", "Salud", "Entretenimiento", "Educación", "Compras", "Viajes", "Finanzas", "Otros"),
            "INCOME" to listOf("Sueldo", "Honorarios", "Rendimientos", "Comisiones", "Ventas", "Otros ingresos")
        )
        defaults.forEach { (type, names) ->
            names.forEach { name ->
                val cv = ContentValues().apply {
                    put("name", name)
                    put("type", type)
                    putNull("parent_id")
                }
                db.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun Cursor.s(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.d(name: String): Double = getDouble(getColumnIndexOrThrow(name))
    private fun Cursor.l(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.i(name: String): Int = getInt(getColumnIndexOrThrow(name))

    fun addAccount(
        institution: String, name: String, type: String, currency: String,
        creditLimit: Double, cutoffDay: Int,
        dueDay: Int, annualRate: Double
    ): Long {
        val cv = ContentValues().apply {
            put("institution", institution.trim().ifBlank { "Sin banco" })
            put("name", name.trim())
            put("type", type)
            put("currency", currency)
            put("initial_balance", 0.0)
            put("credit_limit", creditLimit)
            put("cutoff_day", cutoffDay)
            put("due_day", dueDay)
            put("annual_rate", annualRate)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("accounts", null, cv)
    }

    fun listAccounts(): List<Account> {
        val result = mutableListOf<Account>()
        readableDatabase.rawQuery("SELECT * FROM accounts WHERE active=1 ORDER BY institution, name", null).use { c ->
            while (c.moveToNext()) {
                val id = c.l("id")
                result += Account(
                    id, c.s("institution"), c.s("name"), c.s("type"), c.s("currency"),
                    c.d("initial_balance"), accountBalance(id, c.s("type"), c.d("initial_balance")),
                    c.d("credit_limit"), c.i("cutoff_day"), c.i("due_day"), c.d("annual_rate")
                )
            }
        }
        return result
    }

    private fun accountBalance(id: Long, type: String, opening: Double): Double =
        accountBalanceAsOf(id, type, opening, LocalDate.now())

    private fun accountBalanceAsOf(id: Long, type: String, opening: Double, asOf: LocalDate): Double {
        if (type == "INVESTMENT" || type == "SAVINGS" || type == "MULTICURRENCY") {
            return investmentResult(id, asOf).currentValue + opening
        }
        val events = mutableListOf<BalanceEvent>()
        readableDatabase.rawQuery(
            "SELECT kind, amount, account_id, transfer_account_id FROM transactions WHERE (account_id=? OR transfer_account_id=?) AND date_iso<=? ORDER BY date_iso,id",
            arrayOf(id.toString(), id.toString(), asOf.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val source = c.getLong(2)
                val target = if (c.isNull(3)) null else c.getLong(3)
                events += BalanceEvent(c.getString(0), c.getDouble(1), source == id, target == id)
            }
        }
        return BalanceCalculator.calculate(opening, type, events)
    }

    private fun investmentResult(
        accountId: Long,
        asOf: LocalDate,
        ignoreYieldMonth: String? = null
    ): InvestmentResult {
        val flows = mutableListOf<InvestmentCashflow>()
        readableDatabase.rawQuery(
            "SELECT kind, amount, account_id, transfer_account_id, date_iso FROM transactions WHERE (account_id=? OR transfer_account_id=?) AND date_iso<=? ORDER BY date_iso,id",
            arrayOf(accountId.toString(), accountId.toString(), asOf.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val kind = c.getString(0)
                val source = c.getLong(2) == accountId
                val target = !c.isNull(3) && c.getLong(3) == accountId
                val amount = c.getDouble(1)
                val signed = when {
                    kind == "INCOME" && source -> amount
                    kind == "EXPENSE" && source -> -amount
                    kind == "TRANSFER" && source -> -amount
                    kind == "TRANSFER" && target -> amount
                    else -> 0.0
                }
                if (signed != 0.0) flows += InvestmentCashflow(c.getString(4), signed)
            }
        }
        val rate = readableDatabase.rawQuery("SELECT annual_rate FROM accounts WHERE id=?", arrayOf(accountId.toString())).use { c ->
            if (c.moveToFirst()) c.getDouble(0) else 0.0
        }
        val entries = mutableListOf<InvestmentYieldEntry>()
        val sql = if (ignoreYieldMonth == null) {
            "SELECT entry_date,amount,entry_type FROM investment_yield_entries WHERE account_id=? AND entry_date<=? ORDER BY entry_date,id"
        } else {
            "SELECT entry_date,amount,entry_type FROM investment_yield_entries WHERE account_id=? AND entry_date<=? AND period_month<>? ORDER BY entry_date,id"
        }
        val args = if (ignoreYieldMonth == null) arrayOf(accountId.toString(), asOf.toString())
            else arrayOf(accountId.toString(), asOf.toString(), ignoreYieldMonth)
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) entries += InvestmentYieldEntry(c.getString(0), c.getDouble(1), c.getString(2))
        }
        return InvestmentCalculator.calculateWithOverrides(flows, rate, entries, asOf)
    }

    private fun requireYieldAccount(accountId: Long) {
        val type = readableDatabase.rawQuery(
            "SELECT type FROM accounts WHERE id=? AND active=1",
            arrayOf(accountId.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        require(type == "INVESTMENT" || type == "SAVINGS" || type == "MULTICURRENCY") {
            "Selecciona una cuenta activa de ahorro, inversión o multidivisa."
        }
    }

    fun saveDailyInvestmentYield(accountId: Long, date: String, amount: Double, note: String) {
        require(amount > 0.0) { "El rendimiento diario debe ser mayor que cero." }
        val parsedDate = LocalDate.parse(date)
        require(!parsedDate.isAfter(LocalDate.now())) { "No puedes registrar un rendimiento con fecha futura." }
        requireYieldAccount(accountId)
        val month = parsedDate.toString().take(7)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("investment_yield_entries", "account_id=? AND period_month=? AND entry_type IN ('MONTHLY','ADJUSTED')",
                arrayOf(accountId.toString(), month))
            val values = ContentValues().apply {
                put("account_id", accountId)
                put("period_month", month)
                put("entry_date", parsedDate.toString())
                put("entry_type", "DAILY")
                put("amount", amount)
                put("note", note.trim())
                put("created_at", System.currentTimeMillis())
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("investment_yield_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun saveMonthlyInvestmentYield(
        accountId: Long,
        month: String,
        date: String,
        amount: Double,
        mode: String,
        note: String
    ) {
        require(mode == "MONTHLY" || mode == "ADJUSTED") { "Modalidad mensual no válida." }
        require(amount >= 0.0) { "El rendimiento no puede ser negativo." }
        val period = java.time.YearMonth.parse(month)
        val parsedDate = LocalDate.parse(date)
        require(!period.isAfter(java.time.YearMonth.from(LocalDate.now()))) { "No puedes registrar rendimientos de un mes futuro." }
        require(!parsedDate.isAfter(LocalDate.now())) { "No puedes registrar un rendimiento con fecha futura." }
        requireYieldAccount(accountId)
        require(parsedDate.year == period.year && parsedDate.monthValue == period.monthValue) {
            "La fecha debe pertenecer al periodo seleccionado."
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("investment_yield_entries", "account_id=? AND period_month=?",
                arrayOf(accountId.toString(), month))
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("account_id", accountId)
                put("period_month", month)
                put("entry_date", parsedDate.toString())
                put("entry_type", mode)
                put("amount", amount)
                put("note", note.trim())
                put("created_at", now)
                put("updated_at", now)
            }
            db.insertOrThrow("investment_yield_entries", null, values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun listInvestmentYieldEntries(accountId: Long, month: String): List<InvestmentYieldEntryRecord> {
        val result = mutableListOf<InvestmentYieldEntryRecord>()
        readableDatabase.rawQuery(
            "SELECT id,entry_date,amount,entry_type,note FROM investment_yield_entries WHERE account_id=? AND period_month=? ORDER BY entry_date,id",
            arrayOf(accountId.toString(), month)
        ).use { c ->
            while (c.moveToNext()) {
                result += InvestmentYieldEntryRecord(
                    c.getLong(0), c.getString(1), c.getDouble(2), c.getString(3), c.getString(4)
                )
            }
        }
        return result
    }

    fun deleteInvestmentYieldEntry(id: Long) {
        writableDatabase.delete("investment_yield_entries", "id=?", arrayOf(id.toString()))
    }

    fun clearMonthlyInvestmentYields(accountId: Long, month: String) {
        java.time.YearMonth.parse(month)
        writableDatabase.delete("investment_yield_entries", "account_id=? AND period_month=?",
            arrayOf(accountId.toString(), month))
    }

    fun investmentYieldMonthSummary(accountId: Long, month: String): InvestmentYieldMonthSummary {
        val period = java.time.YearMonth.parse(month)
        val today = LocalDate.now()
        require(!period.isAfter(java.time.YearMonth.from(today))) { "No puedes registrar rendimientos de un mes futuro." }
        val asOf = if (period == java.time.YearMonth.from(today)) today else period.atEndOfMonth()
        val beforeMonth = period.atDay(1).minusDays(1)
        val opening = investmentResult(accountId, beforeMonth)
        val automaticEnd = investmentResult(accountId, asOf, ignoreYieldMonth = month)
        val effectiveEnd = investmentResult(accountId, asOf)
        val flowTotal = readableDatabase.rawQuery(
            """SELECT COALESCE(SUM(CASE
                WHEN kind='INCOME' AND account_id=? THEN amount
                WHEN kind='EXPENSE' AND account_id=? THEN -amount
                WHEN kind='TRANSFER' AND account_id=? THEN -amount
                WHEN kind='TRANSFER' AND transfer_account_id=? THEN amount
                ELSE 0 END),0)
               FROM transactions WHERE (account_id=? OR transfer_account_id=?)
               AND substr(date_iso,1,7)=? AND date_iso<=?""",
            arrayOf(accountId.toString(), accountId.toString(), accountId.toString(), accountId.toString(),
                accountId.toString(), accountId.toString(), month, asOf.toString())
        ).use { c -> c.moveToFirst(); c.getDouble(0) }
        val rows = mutableListOf<InvestmentYieldEntry>()
        readableDatabase.rawQuery(
            "SELECT entry_date,amount,entry_type FROM investment_yield_entries WHERE account_id=? AND period_month=? ORDER BY entry_date,id",
            arrayOf(accountId.toString(), month)
        ).use { c ->
            while (c.moveToNext()) rows += InvestmentYieldEntry(c.getString(0), c.getDouble(1), c.getString(2))
        }
        val mode = when {
            rows.any { it.type == "ADJUSTED" } -> "ADJUSTED"
            rows.any { it.type == "MONTHLY" } -> "MONTHLY"
            rows.any { it.type == "DAILY" } -> "DAILY"
            else -> "AUTO"
        }
        val estimated = automaticEnd.currentValue - opening.currentValue - flowTotal
        val effective = effectiveEnd.currentValue - opening.currentValue - flowTotal
        val currency = readableDatabase.rawQuery("SELECT currency FROM accounts WHERE id=?", arrayOf(accountId.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else "MXN"
        }
        return InvestmentYieldMonthSummary(
            accountId, month, currency, estimated, effective, mode, rows.size, rows.sumOf { it.amount }
        )
    }

    fun checkpoint() {
        readableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { while (it.moveToNext()) { /* consume */ } }
    }

    fun integrityCheck(): Boolean = readableDatabase.rawQuery("PRAGMA quick_check", null).use { c ->
        c.moveToFirst() && c.getString(0).equals("ok", true)
    }

    fun lastModifiedAt(): Long = readableDatabase.rawQuery("SELECT last_modified FROM sync_meta WHERE id=1", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    fun listCategories(type: String? = null): List<Category> {
        val result = mutableListOf<Category>()
        val sql = if (type == null) "SELECT * FROM categories ORDER BY type, name" else
            "SELECT * FROM categories WHERE type=? ORDER BY name"
        readableDatabase.rawQuery(sql, if (type == null) null else arrayOf(type)).use { c ->
            while (c.moveToNext()) {
                result += Category(
                    c.l("id"), c.s("name"), c.s("type"),
                    if (c.isNull(c.getColumnIndexOrThrow("parent_id"))) null else c.l("parent_id")
                )
            }
        }
        return result
    }

    fun addCategory(name: String, type: String, parentId: Long?): Long {
        val cv = ContentValues().apply {
            put("name", name.trim())
            put("type", type)
            if (parentId == null) putNull("parent_id") else put("parent_id", parentId)
        }
        return writableDatabase.insertWithOnConflict("categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun insertTransaction(
        kind: String, amount: Double, currency: String, categoryId: Long?,
        accountId: Long, transferAccountId: Long?, paymentMethod: String,
        description: String, dateIso: String, receiptUri: String? = null,
        importKey: String? = null, cardStatementId: Long? = null
    ): Long {
        require(amount > 0.0) { "El importe debe ser mayor que cero." }
        val cv = ContentValues().apply {
            put("kind", kind)
            put("amount", amount)
            put("currency", currency)
            if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
            put("account_id", accountId)
            if (transferAccountId == null) putNull("transfer_account_id") else put("transfer_account_id", transferAccountId)
            put("payment_method", paymentMethod)
            put("description", description)
            put("date_iso", dateIso)
            if (receiptUri == null) putNull("receipt_uri") else put("receipt_uri", receiptUri)
            if (importKey == null) putNull("import_key") else put("import_key", importKey)
            put("created_at", System.currentTimeMillis())
        }
        val database = writableDatabase
        database.beginTransaction()
        try {
            val transactionId = database.insertOrThrow("transactions", null, cv)
            if (kind == "TRANSFER" && transferAccountId != null && dateIso <= LocalDate.now().toString()) {
                val targetType = database.rawQuery("SELECT type FROM accounts WHERE id=?", arrayOf(transferAccountId.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                val sourceType = database.rawQuery("SELECT type FROM accounts WHERE id=?", arrayOf(accountId.toString())).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                if (targetType == "CREDIT_CARD" && sourceType in setOf("CHECKING", "SAVINGS", "CASH", "MULTICURRENCY")) {
                    val statementIds = if (cardStatementId != null) listOf(cardStatementId) else {
                        val ids = mutableListOf<Long>()
                        database.rawQuery(
                            """SELECT s.id FROM card_statements s LEFT JOIN statement_payments p ON p.statement_id=s.id
                               WHERE s.account_id=? AND s.closing_date<=? GROUP BY s.id,s.statement_balance,s.due_date
                               HAVING COALESCE(SUM(p.amount),0)<s.statement_balance ORDER BY s.due_date ASC""",
                            arrayOf(transferAccountId.toString(), dateIso)
                        ).use { c -> while (c.moveToNext()) ids += c.getLong(0) }
                        ids
                    }
                    var unallocated = amount
                    statementIds.forEach { statementId ->
                        if (unallocated > 0.009) {
                            val remaining = database.rawQuery(
                                """SELECT s.statement_balance-COALESCE(SUM(p.amount),0),s.reminder_id
                                   FROM card_statements s LEFT JOIN statement_payments p ON p.statement_id=s.id
                                   WHERE s.id=? GROUP BY s.id,s.statement_balance,s.reminder_id""", arrayOf(statementId.toString())
                            ).use { c -> if (c.moveToFirst()) (c.getDouble(0).coerceAtLeast(0.0) to if (c.isNull(1)) null else c.getLong(1)) else (0.0 to null) }
                            val allocated = minOf(unallocated, remaining.first)
                            if (allocated > 0.009) {
                                val payment = ContentValues().apply {
                                    put("statement_id", statementId); put("transaction_id", transactionId)
                                    put("amount", allocated); put("created_at", System.currentTimeMillis())
                                }
                                database.insertWithOnConflict("statement_payments", null, payment, SQLiteDatabase.CONFLICT_IGNORE)
                                unallocated -= allocated
                                val updatedRemaining = (remaining.first - allocated).coerceAtLeast(0.0)
                                remaining.second?.let { reminderId ->
                                    if (updatedRemaining <= 0.009) markReminderDone(reminderId)
                                    else updateReminderAmount(reminderId, updatedRemaining)
                                }
                            }
                        }
                    }
                }
            }
            database.setTransactionSuccessful()
            return transactionId
        } finally { database.endTransaction() }
    }

    fun listTransactions(limit: Int = 150): List<TxItem> {
        val result = mutableListOf<TxItem>()
        readableDatabase.rawQuery(
            """SELECT t.*, COALESCE(c.name,'Transferencia') AS category_name,
               COALESCE(a.name,'') AS account_name, COALESCE(b.name,'') AS transfer_name
               FROM transactions t
               LEFT JOIN categories c ON c.id=t.category_id
               LEFT JOIN accounts a ON a.id=t.account_id
               LEFT JOIN accounts b ON b.id=t.transfer_account_id
               ORDER BY t.date_iso DESC, t.id DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result += TxItem(
                    c.l("id"), c.s("kind"), c.d("amount"), c.s("currency"),
                    c.s("category_name"), c.s("account_name"), c.s("transfer_name"),
                    c.s("payment_method"), c.s("description"), c.s("date_iso")
                )
            }
        }
        readableDatabase.rawQuery(
            """SELECT y.id,y.entry_date,y.amount,y.entry_type,y.note,a.name,a.currency
               FROM investment_yield_entries y
               JOIN accounts a ON a.id=y.account_id
               ORDER BY y.entry_date DESC,y.id DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val note = c.s("note")
                val typeLabel = when (c.s("entry_type")) {
                    "DAILY" -> "diario"
                    "MONTHLY" -> "mensual"
                    else -> "ajustado"
                }
                result += TxItem(
                    -c.l("id"), "INCOME", c.d("amount"), c.s("currency"),
                    "Rendimientos", c.s("name"), "", "RENDIMIENTO",
                    if (note.isBlank()) "Rendimiento confirmado ($typeLabel)" else "Rendimiento confirmado ($typeLabel): $note",
                    c.s("entry_date")
                )
            }
        }
        return result.sortedWith(
            compareByDescending<TxItem> { it.date }.thenByDescending { it.id }
        ).take(limit)
    }

    fun monthlyTotals(month: String): MonthlyTotals {
        var income = 0.0
        var expense = 0.0
        readableDatabase.rawQuery(
            """SELECT kind, COALESCE(SUM(amount),0) total FROM transactions
               WHERE substr(date_iso,1,7)=? AND kind IN ('INCOME','EXPENSE')
               GROUP BY kind""",
            arrayOf(month)
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == "INCOME") income = c.getDouble(1) else expense = c.getDouble(1)
            }
        }
        return MonthlyTotals(income, expense)
    }

    fun monthlyTotalsByCurrency(month: String): List<CurrencyMonthlyTotals> {
        val grouped = linkedMapOf<String, Pair<Double, Double>>()
        val query = if (month == YearMonth.now().toString())
            """SELECT currency, kind, COALESCE(SUM(amount),0) total FROM transactions
               WHERE substr(date_iso,1,7)=? AND date_iso<=? AND kind IN ('INCOME','EXPENSE')
               GROUP BY currency, kind ORDER BY currency"""
        else """SELECT currency, kind, COALESCE(SUM(amount),0) total FROM transactions
               WHERE substr(date_iso,1,7)=? AND kind IN ('INCOME','EXPENSE')
               GROUP BY currency, kind ORDER BY currency"""
        val args = if (month == YearMonth.now().toString()) arrayOf(month, LocalDate.now().toString()) else arrayOf(month)
        readableDatabase.rawQuery(query, args).use { c ->
            while (c.moveToNext()) {
                val currency = c.getString(0)
                val old = grouped[currency] ?: (0.0 to 0.0)
                grouped[currency] = if (c.getString(1) == "INCOME") c.getDouble(2) to old.second
                    else old.first to c.getDouble(2)
            }
        }
        investmentYieldTotalsByCurrency(month).forEach { (currency, amount) ->
            val old = grouped[currency] ?: (0.0 to 0.0)
            grouped[currency] = (old.first + amount) to old.second
        }
        return grouped.map { CurrencyMonthlyTotals(it.key, it.value.first, it.value.second) }
    }

    private fun investmentYieldTotalsByCurrency(month: String): Map<String, Double> {
        val totals = linkedMapOf<String, Double>()
        listAccounts().filter { it.type == "INVESTMENT" || it.type == "SAVINGS" ||
            (it.type == "MULTICURRENCY" && (it.annualRate > 0.0 || hasInvestmentYieldEntries(it.id))) }.forEach { account ->
            val summary = runCatching { investmentYieldMonthSummary(account.id, month) }.getOrNull() ?: return@forEach
            // Only confirmed/manual yields are realized income. Automatic estimates affect
            // the projected account value, but are not reported as actual monthly income.
            if (summary.mode != "AUTO") {
                totals[account.currency] = (totals[account.currency] ?: 0.0) + summary.recordedAmount
            }
        }
        return totals
    }

    fun monthlyCashFlowTotalsByCurrency(month: String): List<CurrencyMonthlyTotals> {
        val types = listAccounts().associate { it.id to it.type }
        val liquid = setOf("CHECKING", "SAVINGS", "CASH", "MULTICURRENCY")
        val grouped = linkedMapOf<String, Pair<Double, Double>>()
        val currentMonth = YearMonth.now().toString()
        val query = if (month == currentMonth)
            "SELECT kind,amount,currency,account_id,transfer_account_id FROM transactions WHERE substr(date_iso,1,7)=? AND date_iso<=?"
        else "SELECT kind,amount,currency,account_id,transfer_account_id FROM transactions WHERE substr(date_iso,1,7)=?"
        val args = if (month == currentMonth) arrayOf(month, LocalDate.now().toString()) else arrayOf(month)
        readableDatabase.rawQuery(query, args).use { c ->
            while (c.moveToNext()) {
                val kind = c.getString(0)
                val amount = c.getDouble(1)
                val currency = c.getString(2)
                val sourceType = types[c.getLong(3)].orEmpty()
                val targetType = if (c.isNull(4)) "" else types[c.getLong(4)].orEmpty()
                val old = grouped[currency] ?: (0.0 to 0.0)
                var income = 0.0
                var expense = 0.0
                when (kind) {
                    "INCOME" -> if (sourceType in liquid) income = amount
                    "EXPENSE" -> if (sourceType in liquid) expense = amount
                    "TRANSFER" -> {
                        if (sourceType in liquid && targetType !in liquid) expense = amount
                        else if (sourceType !in liquid && targetType in liquid) income = amount
                    }
                }
                grouped[currency] = (old.first + income) to (old.second + expense)
            }
        }
        return grouped.map { CurrencyMonthlyTotals(it.key, it.value.first, it.value.second) }
    }

    private fun firstTransactionMonth(currency: String): YearMonth? =
        readableDatabase.rawQuery("SELECT MIN(date_iso) FROM transactions WHERE currency=?", arrayOf(currency)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) runCatching { YearMonth.from(LocalDate.parse(c.getString(0))) }.getOrNull() else null
        }

    fun netWorthByCurrency(): List<CurrencyNetWorth> {
        val result = mutableListOf<CurrencyNetWorth>()
        val accounts = listAccounts()
        accounts.groupBy { it.currency }.forEach { (currency, group) ->
            val assets = group.filter { it.type != "CREDIT_CARD" && it.type != "LOAN" }.sumOf { it.balance }
            val liabilities = group.filter { it.type == "CREDIT_CARD" || it.type == "LOAN" }.sumOf { it.balance }
            result += CurrencyNetWorth(currency, assets, liabilities)
        }
        return result.sortedBy { it.currency }
    }

    private fun hasInvestmentYieldEntries(accountId: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM investment_yield_entries WHERE account_id=?",
            arrayOf(accountId.toString())
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }

    fun investmentSummaries(): List<InvestmentSummary> = listAccounts()
        .filter { it.type == "INVESTMENT" || it.type == "SAVINGS" ||
            (it.type == "MULTICURRENCY" && (it.annualRate > 0.0 || hasInvestmentYieldEntries(it.id))) }
        .map { account ->
            val result = investmentResult(account.id, LocalDate.now())
            InvestmentSummary(account.id, account.name, account.currency, result.principal,
                result.currentValue, result.accruedInterest, account.annualRate)
        }

    fun recordNetWorthSnapshot(date: LocalDate = LocalDate.now()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            netWorthByCurrency().forEach { item ->
                val values = ContentValues().apply {
                    put("snapshot_date", date.toString())
                    put("currency", item.currency)
                    put("assets", item.assets)
                    put("liabilities", item.liabilities)
                }
                db.insertWithOnConflict("net_worth_snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun netWorthHistory(currency: String, limit: Int = 90): List<NetWorthPoint> {
        val result = mutableListOf<NetWorthPoint>()
        readableDatabase.rawQuery(
            "SELECT snapshot_date,assets,liabilities FROM net_worth_snapshots WHERE currency=? ORDER BY snapshot_date DESC LIMIT ?",
            arrayOf(currency, limit.toString())
        ).use { c -> while (c.moveToNext()) result += NetWorthPoint(c.getString(0), c.getDouble(1), c.getDouble(2)) }
        return result.reversed()
    }

    fun ensureCreditCardStatements(today: LocalDate = LocalDate.now()) {
        listAccounts().filter { it.type == "CREDIT_CARD" && it.cutoffDay in 1..31 && it.dueDay in 1..31 }.forEach { account ->
            val accountCreatedDate = java.time.Instant.ofEpochMilli(accountCreatedAt(account.id)).atZone(ZoneId.systemDefault()).toLocalDate()
            val createdMonth = YearMonth.from(accountCreatedDate)
            var month = createdMonth
            val lastMonth = YearMonth.from(today)
            var guard = 0
            while (!month.isAfter(lastMonth) && guard++ < 240) {
                val closingDate = month.atDay(account.cutoffDay.coerceAtMost(month.lengthOfMonth()))
                if (!closingDate.isAfter(today) && closingDate.isAfter(accountCreatedDate)) {
                    val dueMonth = month.plusMonths(1)
                    val dueDate = dueMonth.atDay(account.dueDay.coerceAtMost(dueMonth.lengthOfMonth()))
                    val exists = readableDatabase.rawQuery(
                        "SELECT COUNT(*) FROM card_statements WHERE account_id=? AND period=?",
                        arrayOf(account.id.toString(), month.toString())
                    ).use { c -> c.moveToFirst(); c.getInt(0) > 0 }
                    if (!exists) {
                        val closingBalance = accountBalanceAsOf(account.id, account.type, 0.0, closingDate).coerceAtLeast(0.0)
                        val cv = ContentValues().apply {
                            put("account_id", account.id); put("period", month.toString())
                            put("closing_date", closingDate.toString()); put("due_date", dueDate.toString())
                            put("statement_balance", closingBalance); put("minimum_payment", 0.0)
                            put("generated_at", System.currentTimeMillis())
                        }
                        val statementId = writableDatabase.insertWithOnConflict("card_statements", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                        if (statementId != -1L && closingBalance > 0.0) {
                            val dueAt = dueDate.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val reminderId = addReminder("Pago tarjeta ${account.name} · $month", closingBalance, account.currency, dueAt, "WEEKLY", account.id)
                            writableDatabase.execSQL("UPDATE card_statements SET reminder_id=? WHERE id=?", arrayOf(reminderId, statementId))
                        }
                    }
                }
                month = month.plusMonths(1)
            }
        }
    }

    private fun accountCreatedAt(accountId: Long): Long = readableDatabase.rawQuery(
        "SELECT created_at FROM accounts WHERE id=?", arrayOf(accountId.toString())
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else System.currentTimeMillis() }

    fun listCreditStatements(): List<CreditStatement> {
        val result = mutableListOf<CreditStatement>()
        readableDatabase.rawQuery(
            """SELECT s.id,s.account_id,a.name,a.currency,s.period,s.closing_date,s.due_date,
               s.statement_balance,s.minimum_payment,COALESCE(SUM(p.amount),0) paid,
               COALESCE(SUM(CASE WHEN t.date_iso<=s.due_date THEN p.amount ELSE 0 END),0) paid_by_due
               FROM card_statements s JOIN accounts a ON a.id=s.account_id
               LEFT JOIN statement_payments p ON p.statement_id=s.id
               LEFT JOIN transactions t ON t.id=p.transaction_id
               GROUP BY s.id,s.account_id,a.name,a.currency,s.period,s.closing_date,s.due_date,s.statement_balance,s.minimum_payment
               ORDER BY s.due_date DESC""", null
        ).use { c -> while (c.moveToNext()) result += CreditStatement(
            c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),
            c.getString(6),c.getDouble(7),c.getDouble(8),c.getDouble(9),c.getDouble(10)
        ) }
        return result
    }

    fun setStatementMinimum(statementId: Long, minimum: Double) {
        require(minimum >= 0.0)
        writableDatabase.execSQL("UPDATE card_statements SET minimum_payment=? WHERE id=?", arrayOf(minimum, statementId))
    }

    fun recordCardPayment(statementId: Long, sourceAccountId: Long, amount: Double, date: String, note: String): Long {
        require(amount > 0.0)
        val paymentDate = LocalDate.parse(date)
        val statement = listCreditStatements().firstOrNull { it.id == statementId } ?: error("No existe el estado de cuenta.")
        require(!paymentDate.isBefore(LocalDate.parse(statement.closingDate))) { "La fecha del pago no puede ser anterior al corte del estado de cuenta." }
        require(!paymentDate.isAfter(LocalDate.now())) { "Registra pagos realizados; para programar un pago futuro utiliza un compromiso de flujo de efectivo." }
        require(amount <= statement.remaining + 0.01) { "El pago supera el saldo pendiente del estado de cuenta." }
        val source = listAccounts().firstOrNull { it.id == sourceAccountId } ?: error("Selecciona una cuenta de origen.")
        require(source.currency == statement.currency) { "La cuenta de origen debe tener la misma moneda que la tarjeta." }
        require(source.type in setOf("CHECKING", "SAVINGS", "CASH", "MULTICURRENCY")) { "Selecciona una cuenta de cheques, ahorro, efectivo o divisa para pagar la tarjeta." }
        require(source.balance + 0.01 >= amount) { "El saldo disponible de la cuenta de origen no alcanza para este pago." }
        return insertTransaction("TRANSFER", amount, statement.currency, null, sourceAccountId,
            statement.accountId, "TRANSFER", note.ifBlank { "Pago estado de cuenta ${statement.period}" }, date,
            cardStatementId = statementId)
    }

    fun addCashFlowPlan(title: String, kind: String, amount: Double, currency: String, nextDate: String, repeatRule: String): Long {
        require(title.isNotBlank() && amount > 0.0)
        LocalDate.parse(nextDate)
        val cv = ContentValues().apply {
            put("title", title.trim()); put("kind", kind); put("amount", amount)
            put("currency", currency); put("next_date", nextDate); put("repeat_rule", repeatRule)
            put("active", 1); put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("cashflow_plans", null, cv)
    }

    fun listCashFlowPlans(): List<CashFlowPlan> {
        val result = mutableListOf<CashFlowPlan>()
        readableDatabase.rawQuery("SELECT * FROM cashflow_plans WHERE active=1 ORDER BY next_date", null).use { c ->
            while (c.moveToNext()) result += CashFlowPlan(c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("title")), c.getString(c.getColumnIndexOrThrow("kind")),
                c.getDouble(c.getColumnIndexOrThrow("amount")), c.getString(c.getColumnIndexOrThrow("currency")),
                c.getString(c.getColumnIndexOrThrow("next_date")), c.getString(c.getColumnIndexOrThrow("repeat_rule")),
                c.getInt(c.getColumnIndexOrThrow("active")) == 1)
        }
        return result
    }

    fun deleteCashFlowPlan(id: Long) { writableDatabase.execSQL("UPDATE cashflow_plans SET active=0 WHERE id=?", arrayOf(id)) }

    fun cashFlowForecast(months: Int = 6): List<CashFlowProjection> {
        val today = LocalDate.now()
        val allAccounts = listAccounts()
        val currencies = (allAccounts.map { it.currency } + listCashFlowPlans().map { it.currency }).distinct().sorted()
        val opening = allAccounts.groupBy { it.currency }.mapValues { (_, group) ->
            group.filter { it.type in setOf("CHECKING", "SAVINGS", "CASH", "MULTICURRENCY") }.sumOf { it.balance }
        }.toMutableMap()
        val currentMonth = YearMonth.now()
        val lastCompletedMonth = currentMonth.minusMonths(1)
        val averages = currencies.associateWith { currency ->
            val firstMonth = firstTransactionMonth(currency)
            val monthsAvailable = if (firstMonth == null || firstMonth.isAfter(lastCompletedMonth)) 0
                else java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, lastCompletedMonth).toInt().plus(1).coerceIn(1, 3)
            val previous = (1..monthsAvailable).map { lastCompletedMonth.minusMonths((it - 1).toLong()).toString() }
            val totals = previous.map { month -> monthlyCashFlowTotalsByCurrency(month).firstOrNull { row -> row.currency == currency } ?: CurrencyMonthlyTotals(currency,0.0,0.0) }
            val divisor = monthsAvailable.coerceAtLeast(1).toDouble()
            (totals.sumOf { it.income } / divisor) to (totals.sumOf { it.expense } / divisor)
        }
        val plans = listCashFlowPlans()
        val reminders = pendingReminders()
        val result = mutableListOf<CashFlowProjection>()
        repeat(months.coerceIn(1, 24)) { index ->
            val month = YearMonth.from(today).plusMonths(index.toLong())
            currencies.forEach { currency ->
                val average = averages[currency] ?: (0.0 to 0.0)
                val actual = if (index == 0) monthlyCashFlowTotalsByCurrency(month.toString()).firstOrNull { it.currency == currency } ?: CurrencyMonthlyTotals(currency,0.0,0.0) else CurrencyMonthlyTotals(currency,0.0,0.0)
                var income = if (index == 0) (average.first - actual.income).coerceAtLeast(0.0) else average.first
                var expense = if (index == 0) (average.second - actual.expense).coerceAtLeast(0.0) else average.second
                plans.filter { it.currency == currency }.forEach planLoop@{ plan ->
                    var occurrence = runCatching { LocalDate.parse(plan.nextDate) }.getOrNull() ?: return@planLoop
                    var guard = 0
                    while (occurrence.isBefore(month.atDay(1)) && guard++ < 240) {
                        occurrence = nextOccurrence(occurrence, plan.repeatRule) ?: break
                    }
                    guard = 0
                    while (!occurrence.isAfter(month.atEndOfMonth()) && guard++ < 60) {
                        if (occurrence >= month.atDay(1)) {
                            if (plan.kind == "INCOME") income += plan.amount else expense += plan.amount
                        }
                        occurrence = nextOccurrence(occurrence, plan.repeatRule) ?: break
                    }
                }
                val scheduledReminderExpense = reminders.filter { it.currency == currency && it.amount > 0.0 }.sumOf { reminder ->
                    val due = java.time.Instant.ofEpochMilli(reminder.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    if (YearMonth.from(due) == month) reminder.amount else 0.0
                }
                expense = maxOf(expense, scheduledReminderExpense)
                val openingBalance = opening[currency] ?: 0.0
                val closingBalance = CashFlowMath.closing(openingBalance, income, expense)
                result += CashFlowProjection(month.toString(), currency, openingBalance, income, expense, closingBalance)
                opening[currency] = closingBalance
            }
        }
        return result
    }

    private fun nextOccurrence(date: LocalDate, rule: String): LocalDate? = when (rule) {
        "WEEKLY" -> date.plusWeeks(1)
        "MONTHLY" -> date.plusMonths(1)
        "YEARLY" -> date.plusYears(1)
        else -> null
    }

    fun convertAmount(amount: Double, from: String, to: String): Double? {
        if (from == to) return amount
        val direct = readableDatabase.rawQuery("SELECT rate FROM exchange_rates WHERE base_currency=? AND quote_currency=?", arrayOf(from,to)).use { c -> if (c.moveToFirst()) c.getDouble(0) else null }
        if (direct != null && direct > 0) return amount * direct
        val inverse = readableDatabase.rawQuery("SELECT rate FROM exchange_rates WHERE base_currency=? AND quote_currency=?", arrayOf(to,from)).use { c -> if (c.moveToFirst()) c.getDouble(0) else null }
        return if (inverse != null && inverse > 0) amount / inverse else null
    }

    fun upsertBudget(month: String, categoryId: Long, amount: Double, currency: String) {
        val cv = ContentValues().apply {
            put("month", month)
            put("category_id", categoryId)
            put("amount", amount)
            put("currency", currency)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("budgets", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun copyBudgets(fromMonth: String, toMonth: String): Int {
        val values = ContentValues()
        var count = 0
        readableDatabase.rawQuery(
            "SELECT category_id, amount, currency FROM budgets WHERE month=?",
            arrayOf(fromMonth)
        ).use { c ->
            while (c.moveToNext()) {
                values.clear()
                values.put("month", toMonth)
                values.put("category_id", c.getLong(0))
                values.put("amount", c.getDouble(1))
                values.put("currency", c.getString(2))
                values.put("updated_at", System.currentTimeMillis())
                val insertedId = writableDatabase.insertWithOnConflict("budgets", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (insertedId != -1L) count++
            }
        }
        return count
    }

    fun propagateBudget(categoryId: Long, fromMonth: String, amount: Double, currency: String, months: Int = 24) {
        var ym = YearMonth.parse(fromMonth)
        repeat(months.coerceIn(1, 60)) {
            upsertBudget(ym.toString(), categoryId, amount, currency)
            ym = ym.plusMonths(1)
        }
    }

    fun budgetSummaries(month: String): List<BudgetSummary> {
        val result = mutableListOf<BudgetSummary>()
        readableDatabase.rawQuery(
            """SELECT b.category_id, c.name, c.type, b.amount, b.currency,
               COALESCE(SUM(t.amount),0) AS actual
               FROM budgets b
               JOIN categories c ON c.id=b.category_id
               LEFT JOIN transactions t
                 ON t.category_id=b.category_id
                AND substr(t.date_iso,1,7)=b.month
                AND t.kind=c.type
                AND t.currency=b.currency
               WHERE b.month=?
               GROUP BY b.category_id, c.name, c.type, b.amount, b.currency
               ORDER BY c.type, c.name""",
            arrayOf(month)
        ).use { c ->
            while (c.moveToNext()) {
                result += BudgetSummary(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getDouble(5), c.getString(4))
            }
        }
        val yieldTotals = investmentYieldTotalsByCurrency(month)
        if (yieldTotals.isNotEmpty()) {
            result.indices.forEach { index ->
                val row = result[index]
                if (row.categoryType == "INCOME" && row.categoryName.equals("Rendimientos", ignoreCase = true)) {
                    result[index] = row.copy(actual = row.actual + (yieldTotals[row.currency] ?: 0.0))
                }
            }
        }
        return result
    }

    fun markBudgetAlertSent(month: String, categoryId: Long, currency: String, threshold: Int): Boolean {
        val cv = ContentValues().apply {
            put("month", month)
            put("category_id", categoryId)
            put("currency", currency)
            put("threshold", threshold)
            put("sent_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            "budget_alerts", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun addReminder(title: String, amount: Double, currency: String, dueAt: Long, repeatRule: String, accountId: Long?): Long {
        val cv = ContentValues().apply {
            put("title", title)
            put("amount", amount)
            put("currency", currency)
            put("due_at", dueAt)
            put("repeat_rule", repeatRule)
            if (accountId == null) putNull("account_id") else put("account_id", accountId)
            put("done", 0.toInt())
        }
        return writableDatabase.insertOrThrow("reminders", null, cv)
    }

    fun listReminders(): List<ReminderItem> {
        val result = mutableListOf<ReminderItem>()
        readableDatabase.rawQuery("SELECT * FROM reminders ORDER BY done, due_at", null).use { c ->
            while (c.moveToNext()) {
                result += ReminderItem(
                    c.l("id"), c.s("title"), c.d("amount"), c.s("currency"), c.l("due_at"),
                    c.s("repeat_rule"), if (c.isNull(c.getColumnIndexOrThrow("account_id"))) null else c.l("account_id"),
                    c.i("done") == 1
                )
            }
        }
        return result
    }

    fun pendingReminders(): List<ReminderItem> = listReminders().filter { !it.done }

    fun markReminderDone(id: Long) {
        writableDatabase.execSQL("UPDATE reminders SET done=1 WHERE id=?", arrayOf(id))
    }

    fun updateReminderAmount(id: Long, amount: Double) {
        writableDatabase.execSQL("UPDATE reminders SET amount=? WHERE id=?", arrayOf(amount, id))
    }

    fun updateReminderDue(id: Long, dueAt: Long) {
        writableDatabase.execSQL("UPDATE reminders SET due_at=? WHERE id=?", arrayOf(dueAt, id))
    }

    fun upsertRate(base: String, quote: String, rate: Double) {
        val cv = ContentValues().apply {
            put("base_currency", base)
            put("quote_currency", quote)
            put("rate", rate)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("exchange_rates", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listRates(): List<Triple<String, String, Double>> {
        val result = mutableListOf<Triple<String, String, Double>>()
        readableDatabase.rawQuery("SELECT base_currency, quote_currency, rate FROM exchange_rates ORDER BY base_currency, quote_currency", null).use { c ->
            while (c.moveToNext()) result += Triple(c.getString(0), c.getString(1), c.getDouble(2))
        }
        return result
    }

    fun exportTransactionsCsv(): String {
        val rows = listTransactions(100000)
        val out = StringBuilder("Fecha,Tipo,Importe,Moneda,Categoria,Cuenta,Medio,Descripcion\r\n")
        rows.asReversed().forEach { tx ->
            val values = listOf(
                tx.date, tx.kind, tx.amount.toString(), tx.currency, tx.categoryName,
                tx.accountName, tx.paymentMethod, tx.description
            )
            out.append(values.joinToString(",") { csvEscape(it) }).append("\r\n")
        }
        return out.toString()
    }

    private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
