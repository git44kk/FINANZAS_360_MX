package mx.finanzas360.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth
import java.util.concurrent.TimeUnit

 data class AiSettings(
    val endpoint: String = "https://api.openai.com/v1",
    val model: String = "gpt-4.1-mini",
    val apiKey: String = "",
    val useOnline: Boolean = false
)

object AiSettingsStore {
    private const val ENDPOINT = "ai_endpoint"
    private const val MODEL = "ai_model"
    private const val API_KEY = "ai_api_key"
    private const val USE_ONLINE = "ai_use_online"
    private const val PREFS = "finanzas360_ai_settings"

    fun load(context: Context): AiSettings = AiSettings(
        endpoint = SecureVault.get(context, ENDPOINT) ?: "https://api.openai.com/v1",
        model = SecureVault.get(context, MODEL) ?: "gpt-4.1-mini",
        apiKey = SecureVault.get(context, API_KEY).orEmpty(),
        useOnline = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(USE_ONLINE, false)
    )

    fun save(context: Context, settings: AiSettings) {
        SecureVault.put(context, ENDPOINT, settings.endpoint.trim())
        SecureVault.put(context, MODEL, settings.model.trim())
        SecureVault.put(context, API_KEY, settings.apiKey)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(USE_ONLINE, settings.useOnline).apply()
    }
}

object FinanceAssistantService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    fun ask(context: Context, db: FinanceDb, question: String, settings: AiSettings): String {
        require(question.isNotBlank()) { "Escribe una pregunta." }
        val contextText = buildFinancialContext(db)
        if (!settings.useOnline) return localAnswer(question, contextText)
        require(settings.apiKey.isNotBlank()) { "Configura una API key para activar la IA online." }
        require(settings.endpoint.startsWith("https://", true)) { "El proveedor de IA debe utilizar HTTPS." }
        require(settings.model.isNotBlank()) { "Indica el modelo de IA." }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Eres el asistente financiero de FINANZAS 360 MX. Responde en español, distingue hechos de estimaciones, no inventes tasas ni datos bancarios, no des asesoría de inversión garantizada y advierte cuando falte información. Los importes por moneda no se deben sumar entre sí sin tipo de cambio. Contexto financiero resumido y anonimizado del usuario:\n$contextText"))
            .put(JSONObject().put("role", "user").put("content", question))
        val payload = JSONObject().put("model", settings.model).put("messages", messages).put("temperature", 0.2)
        val request = Request.Builder()
            .url(settings.endpoint.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                error("El proveedor de IA respondió HTTP ${response.code}${detail?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }
            val content = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            return content.ifBlank { "El proveedor no devolvió una respuesta legible." }
        }
    }

    private fun buildFinancialContext(db: FinanceDb): String {
        val month = YearMonth.now().toString()
        val totals = db.monthlyTotalsByCurrency(month).joinToString("\n") {
            "${it.currency}: ingresos ${it.income}, gastos ${it.expense}, ahorro ${it.savings}, tasa de ahorro ${"%.1f".format(FinanceInsights.savingsRate(it.income, it.expense))}%"
        }.ifBlank { "Sin movimientos registrados en el mes actual." }
        val netWorth = db.netWorthByCurrency().joinToString("\n") {
            "${it.currency}: activos ${it.assets}, pasivos ${it.liabilities}, patrimonio ${it.netWorth}"
        }.ifBlank { "Sin cuentas registradas." }
        val budgets = db.budgetSummaries(month).joinToString("\n") {
            "${it.categoryName} (${it.currency}, ${it.categoryType}): presupuesto ${it.budget}, real ${it.actual}"
        }.ifBlank { "Sin presupuestos configurados." }
        val investments = db.investmentSummaries().joinToString("\n") {
            "Cuenta de inversión (${it.currency}): principal ${it.principal}, valor estimado ${it.currentValue}, interés estimado ${it.accruedInterest}, tasa anual configurada ${it.annualRate}%"
        }.ifBlank { "Sin inversiones registradas." }
        val cards = db.listCreditStatements().take(8).joinToString("\n") {
            "Tarjeta (${it.currency}) ${it.period}: saldo de corte ${it.statementBalance}, pagado ${it.paidAmount}, restante ${it.remaining}, fecha límite ${it.dueDate}"
        }.ifBlank { "Sin estados de cuenta generados." }
        val cashflow = db.cashFlowForecast(3).take(12).joinToString("\n") {
            "${it.month} ${it.currency}: entradas ${it.expectedIncome}, salidas ${it.expectedExpense}, saldo proyectado ${it.closingBalance}"
        }.ifBlank { "Sin proyección de flujo disponible." }
        val dueItems = db.pendingReminders().take(6).joinToString("\n") { reminder ->
            val dueDate = java.time.Instant.ofEpochMilli(reminder.dueAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            "Compromiso ${reminder.currency}: ${reminder.amount} con vencimiento ${dueDate}"
        }.ifBlank { "Sin vencimientos pendientes." }
        return "Mes: $month\nMOVIMIENTOS:\n$totals\nPATRIMONIO:\n$netWorth\nPRESUPUESTOS:\n$budgets\nINVERSIONES (estimaciones):\n$investments\nTARJETAS:\n$cards\nFLUJO DE EFECTIVO:\n$cashflow\nVENCIMIENTOS:\n$dueItems"
    }

    private fun localAnswer(question: String, contextText: String): String {
        val q = question.lowercase()
        fun section(start: String, end: String): String =
            contextText.substringAfter(start, "").substringBefore(end).trim().ifBlank { "No hay datos suficientes en esta sección." }
        val (heading, detail) = when {
            listOf("tarjeta", "corte", "pago mínimo", "pago minimo", "no generar intereses").any { it in q } ->
                "Estados de cuenta de tarjetas" to section("TARJETAS:\n", "\nFLUJO DE EFECTIVO:")
            listOf("flujo", "efectivo", "liquidez", "próximos meses", "proximos meses").any { it in q } ->
                "Proyección de flujo de efectivo" to section("FLUJO DE EFECTIVO:\n", "\nVENCIMIENTOS:")
            listOf("presupuesto", "categoría", "categoria", "excedente").any { it in q } ->
                "Ejecución de presupuestos" to section("PRESUPUESTOS:\n", "\nINVERSIONES (estimaciones):")
            listOf("inversión", "inversion", "rendimiento", "interés", "interes", "ahorro con tasa").any { it in q } ->
                "Inversiones y rendimientos estimados" to section("INVERSIONES (estimaciones):\n", "\nTARJETAS:")
            listOf("patrimonio", "deuda", "activo", "pasivo").any { it in q } ->
                "Patrimonio por moneda" to section("PATRIMONIO:\n", "\nPRESUPUESTOS:")
            listOf("ahorro", "gasto", "ingreso").any { it in q } ->
                "Ingresos, gastos y ahorro del mes" to section("MOVIMIENTOS:\n", "\nPATRIMONIO:")
            else -> "Resumen financiero local" to contextText
        }
        return "$heading\n$detail\n\nEste resultado se calculó localmente con tus agregados financieros; no se envió información a internet ni se utilizó un modelo generativo. Para razonamiento conversacional más amplio puedes activar el proveedor online con tu propia API key."
    }
}
