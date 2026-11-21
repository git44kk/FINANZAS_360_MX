# Cambios

## 0.2.0
- Se elimina completamente la importación de Money Lover.
- Todas las cuentas y productos se crean con saldo inicial cero.
- Se sustituye SQLite sin cifrar por SQLCipher; clave protegida por Android Keystore.
- Se agrega bloqueo biométrico/credencial del dispositivo.
- Se actualiza el formato de respaldos cifrados para incluir la clave SQLCipher dentro del contenedor protegido.
- Se agrega sincronización manual y automática por WebDAV HTTPS con detección de conflictos.
- Se agrega seguimiento de estados de cuenta y pagos de tarjetas.
- Se agrega capitalización diaria y proyecciones de inversiones.
- Se agrega flujo de efectivo proyectado con compromisos recurrentes.
- Se agrega historial diario y gráfica de patrimonio.
- Se agrega IA local basada en reglas y conexión opcional a proveedor compatible con OpenAI.
- Se agregan pruebas unitarias de cálculo de inversiones y flujo de efectivo.

## 0.1.0
- Base inicial Android con cuentas, movimientos, presupuestos, recordatorios, OCR, voz, multidivisa y CI en GitHub Actions.


## 0.2.1 — Rendimientos manuales y ajustables
- Registro de rendimientos diarios por cuenta de ahorro, inversión y multidivisa remunerada.
- Captura de un rendimiento total mensual abonado por la institución financiera.
- Ajuste del importe estimado mensual con prellenado del cálculo automático.
- Restauración del cálculo automático por cuenta y mes.
- Los importes manuales/ajustados reemplazan la estimación hasta la fecha de reconocimiento; desde el día siguiente puede continuar la capitalización automática, sin duplicar el periodo ya reconocido.
- Se agrega historial por periodo y eliminación de registros diarios/mensuales para corregir capturas.
- Los rendimientos confirmados aparecen como ingresos en Movimientos y en la exportación CSV.
- Solo los rendimientos manuales/confirmados se incluyen en ingresos mensuales y en la ejecución de la categoría «Rendimientos»; el cálculo automático no infla el ahorro real.
- Base de datos SQLCipher actualizada a esquema v3.


## 0.2.2 — Compatibilidad de compilación Android
- Se actualiza OkHttp a 4.12.1 para mantener compatibilidad con compileSdk 36 y Android Gradle Plugin 8.13.2.
- OkHttp 4.12.1 incorpora la corrección de seguridad para bloquear los ocho certificados intermedios eMudhra comprometidos (CVE-2026-111943).
- Se conserva compileSdk 36, targetSdk 35, minSdk 26 y Gradle 8.13; no se fuerza una actualización mayor de la cadena Android.
- Se actualiza el workflow de GitHub Actions a setup-android v4, sin solicitar el paquete obsoleto `tools`.


## 0.2.3 — Corrección de autenticación biométrica
- MainActivity ahora hereda de androidx.fragment.app.FragmentActivity, que es el tipo requerido por BiometricPrompt 1.1.0.
- Se corrige la resolución del constructor de BiometricPrompt y de authenticate(PromptInfo).
- Se conserva el bloqueo biométrico/credencial del dispositivo; no se elimina ni se desactiva la autenticación.
- No se modifica la configuración Android SDK 36, OkHttp 4.12.1 ni el resto de los módulos financieros.


## 0.2.4 — Inicio sin bloqueo e identidad visual
- La inicialización de SQLCipher, creación/lectura de la clave de base de datos y primera apertura se ejecutan en Dispatchers.IO, no en el hilo principal.
- Se muestra una pantalla de preparación mientras se abre la base de datos.
- Si falla la apertura, se muestra un diagnóstico visible y un botón Reintentar; se registra el error en Logcat con la etiqueta Finanzas360Startup.
- Se declara el icono adaptativo y redondo de FINANZAS 360 MX en el AndroidManifest.
- Se configura AndroidX SplashScreen con fondo oscuro y el icono propio de la app.
- Se conserva la base de datos cifrada, los módulos financieros, bloqueo biométrico, compileSdk 36 y OkHttp 4.12.1.
- El workflow actualiza `actions/upload-artifact` a v6 para utilizar Node.js 24 y retirar la advertencia de Node.js 20.
