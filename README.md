# FINANZAS 360 MX — Android 0.2.4

Aplicación financiera personal Android, local-first y multidivisa. El proyecto utiliza Kotlin, Jetpack Compose, SQLCipher y GitHub Actions para compilar el APK.

## Decisiones de alcance

- **No incluye importación de Money Lover.** Se retiró el importador CSV y no existe dependencia con esa aplicación.
- **Todas las cuentas, tarjetas e inversiones empiezan en $0.00.** No se captura saldo inicial. Los saldos se construyen con movimientos reales que registres desde la fecha de inicio.
- La aplicación funciona offline. La nube y la IA online son opcionales.

## Funciones incluidas en el código

### Registro y control diario
- Cuentas de cheques, ahorro, efectivo, divisas, inversión, tarjetas de crédito y préstamos.
- Ingresos, gastos y transferencias entre cuentas.
- Categorías y subcategorías.
- Captura por voz y lectura OCR de tickets/fotografías.
- Presupuestos mensuales por moneda, copia de mes y propagación a 24 meses.
- Alertas locales de presupuesto al 70%, 85% y 100% y metas de ingreso al 100%.
- Exportación de movimientos a CSV.
- Recordatorios únicos, semanales, mensuales y anuales.

### Seguridad y privacidad
- Base de datos SQLite cifrada con SQLCipher.
- Clave de base de datos aleatoria protegida por Android Keystore.
- Bloqueo de acceso con biometría fuerte o credencial segura del dispositivo.
- Respaldos portables cifrados con AES-GCM y contraseña.
- Las credenciales de WebDAV y la API key de IA se guardan cifradas mediante Android Keystore.
- Android Auto Backup está desactivado para evitar copias del almacenamiento privado sin el flujo cifrado de la app.

### Tarjetas de crédito
- Generación de estado de cuenta cuando llega el día de corte configurado.
- Saldo al corte calculado a partir de los movimientos registrados hasta esa fecha.
- Fecha límite de pago del siguiente mes.
- Captura manual del pago mínimo mostrado por el banco (no se inventa ni se estima).
- Registro de pagos parciales o totales desde una cuenta de la misma moneda.
- Seguimiento de saldo pendiente y si el pago total registrado quedó cubierto antes de la fecha límite.
- El pago para no generar intereses se muestra como saldo al corte pendiente. La app no sustituye el estado de cuenta ni las reglas de cada banco.

### Inversiones
- Rendimiento estimado con capitalización diaria para cuentas de inversión y cuentas de ahorro que tengan tasa anual configurada.
- Depósitos y retiros considerados desde los movimientos registrados.
- Proyecciones a 30, 90 y 365 días.
- Cálculo separado por moneda.
- Las estimaciones no incluyen impuestos, retenciones, comisiones, tasas variables ni condiciones contractuales del producto.

### Flujo de efectivo y patrimonio
- Proyección de seis meses por moneda.
- Calcula flujo de efectivo sobre cuentas líquidas: ingresos/gastos de esas cuentas y transferencias hacia tarjetas, préstamos e inversiones; las compras con tarjeta se reflejan en flujo cuando se registra el pago.
- Promedio de hasta tres meses completos anteriores desde que comenzaste a registrar movimientos, separado de los movimientos de presupuesto.
- Compromisos futuros programados como entradas o salidas únicas, semanales, mensuales o anuales.
- Inclusión de recordatorios con importe en el mes de vencimiento.
- Historial por moneda y gráfica de tendencia, con una captura por día cuando abres la app o actualizas movimientos; no inventa capturas de días en que no se ejecutó.
- Consolidación equivalente en MXN cuando existen tipos de cambio manuales suficientes.

### IA híbrida
- Análisis local disponible sin internet mediante reglas y métricas financieras.
- Conexión opcional con proveedores compatibles con OpenAI Chat Completions.
- Endpoint, modelo y API key configurables; la API key se cifra en el dispositivo.
- Se envían agregados financieros resumidos, no el historial completo de descripciones de movimientos.
- El uso de una API externa requiere una API key y puede generar cargos separados. ChatGPT Plus no incluye automáticamente consumo de API.
- El modo local actual es un motor analítico determinista, no un modelo generativo local.

### Sincronización offline-cloud
- Sincronización de snapshots cifrados mediante WebDAV sobre HTTPS (por ejemplo, Nextcloud o un NAS compatible).
- Funciona localmente cuando no hay internet; los cambios pendientes se sincronizan al recuperar conexión si se activa la sincronización automática.
- Contraseña de cifrado independiente de las credenciales WebDAV.
- Detección de cambios simultáneos. Ante conflicto se conserva el archivo remoto principal y se crea una copia local versionada; no se sobrescribe silenciosamente.
- Resolución explícita del conflicto: elegir este dispositivo como principal (subir) o descartar sus cambios y descargar la nube. La segunda opción reemplaza la base local y solicita confirmación.
- Cada dispositivo que se incorpore debe usar la misma contraseña de cifrado.
- El servidor WebDAV debe permitir HEAD, GET y PUT para el archivo remoto.

## Modelo de saldos

- Cuentas de activo: ingresos y transferencias recibidas aumentan el saldo; gastos y transferencias enviadas lo disminuyen.
- Tarjetas y préstamos: los gastos aumentan la deuda; los pagos/transferencias recibidas la reducen.
- Las transferencias no se consideran ingreso ni gasto.
- Las inversiones aplican capitalización diaria estimada a los flujos netos de cada fecha.
- Los cálculos se separan por moneda. No se suman MXN, USD, EUR u otras monedas sin tipo de cambio.

## Requisitos para compilar

- JDK 17.
- Gradle 8.13.
- Android SDK 36 y Build Tools 36.0.0.
- Android mínimo API 26.

## Compilar localmente

Desde la raíz del proyecto:

```bash
gradle testDebugUnitTest assembleDebug
```

El APK de depuración se genera en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Compilación automática en GitHub

Consulta `INSTRUCCIONES_GITHUB.md`. El workflow `.github/workflows/android-build.yml` compila en cada push a `main` o `master`, en pull requests y manualmente desde Actions. Ejecuta pruebas unitarias y publica el APK `FINANZAS-360-MX-debug` como artefacto.

## Validación pendiente

Esta entrega contiene el código fuente y el workflow. Antes de llamarla versión estable deben completarse:

1. La primera ejecución de GitHub Actions y la corrección de cualquier error de compilación que reporte.
2. Instalación del APK en un dispositivo Android físico.
3. Pruebas reales de cifrado/restauración, bloqueo biométrico, OCR, voz, estados de cuenta, pagos, capitalización, IA y sincronización con un servidor WebDAV.
4. Prueba de sincronización en dos dispositivos y resolución de un conflicto simulado.

No usar datos bancarios reales hasta completar esas pruebas.


## Rendimientos de cuentas de ahorro e inversión (v0.2.1)

Cada cuenta de tipo Ahorro o Inversión, y las cuentas Multidivisa que tengan una tasa o rendimientos registrados, pueden manejar su rendimiento mensual de tres maneras:

1. **Automático:** capitalización diaria estimada con la tasa anual configurada.
2. **Manual diario:** registra el abono real de cada día. En ese mes se desactiva el cálculo automático para evitar duplicar importes.
3. **Manual mensual o ajustado:** registra el total que abonó el banco o corrige el estimado de la app. El importe capturado sustituye el cálculo automático hasta la fecha de reconocimiento; desde el día siguiente continúa la capitalización configurada.

Puedes volver al cálculo automático para cualquier cuenta y mes. Los rendimientos se reflejan en el saldo de la inversión. Los importes manuales/confirmados aparecen también en Movimientos y en la exportación CSV, y se incluyen como ingreso realizado y en la ejecución de la categoría «Rendimientos»; el cálculo automático permanece como estimación y no infla el ahorro real. No registres también el mismo abono como movimiento de ingreso, porque se duplicaría.

La estimación es informativa: las instituciones pueden aplicar bases, límites, retenciones, impuestos, horarios de corte y reglas de saldo promedio distintos. Para conciliar con el estado de cuenta, registra el importe efectivamente abonado o ajusta el cálculo.


### Nota de compilación v0.2.2
La compilación utiliza OkHttp 4.12.1 en lugar de 5.5.1. La versión 5.5.1 del artefacto Android exige compileSdk 37, mientras que este proyecto conserva compileSdk 36 y AGP 8.13.2. OkHttp 4.12.1 incluye la corrección de seguridad CVE-2026-111943 para bloquear los certificados intermedios eMudhra comprometidos, sin obligar a migrar la cadena de compilación a AGP 9.x.


### Nota de compilación v0.2.3
Se corrige la integración de AndroidX Biometric: `MainActivity` hereda de `FragmentActivity`, requerido por el constructor `BiometricPrompt(FragmentActivity, Executor, AuthenticationCallback)` de Biometric 1.1.0. El flujo de bloqueo biométrico se conserva.


### Nota de compilación v0.2.4 — inicio y SQLCipher
La apertura inicial de la base SQLCipher se realiza en un worker thread. La pantalla principal no se compone hasta que la base está abierta; durante ese tiempo se muestra una pantalla de carga. Los errores se registran como `Finanzas360Startup` y se presentan en una pantalla recuperable. Se agregó icono adaptativo y splash screen.

El workflow de GitHub Actions utiliza `actions/upload-artifact@v6`, compatible con Node.js 24.
