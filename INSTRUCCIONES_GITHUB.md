# Subir FINANZAS 360 MX a GitHub y obtener el APK

1. Descarga el ZIP del proyecto y extráelo en tu PC.
2. En GitHub crea un repositorio nuevo llamado `Finanzas360MX`. Recomiendo que sea **Private**.
3. En el repositorio, selecciona **Add file > Upload files**.
4. Arrastra el contenido de la carpeta extraída (no subas únicamente el ZIP). Deben quedar en la raíz `settings.gradle.kts`, `build.gradle.kts`, `app/`, `.github/`, etc.
5. Confirma con **Commit changes**.
6. Abre la pestaña **Actions**. Si aparece un aviso, habilita los workflows.
7. Selecciona **FINANZAS 360 MX · Android CI** y pulsa **Run workflow**. También se ejecutará automáticamente al hacer push a `main` o `master`.
8. Cuando termine en verde, abre la ejecución y descarga **FINANZAS-360-MX-debug** en la sección Artifacts.
9. Extrae el artefacto y transfiere `app-debug.apk` al teléfono para instalarlo.

## Instalación y actualización

- La base de datos de 0.2.4 está cifrada con SQLCipher. Si vienes de 0.2.0, la app actualiza automáticamente el esquema v2 a v3 y conserva las cuentas y movimientos. Si instalaste una compilación de prueba 0.1.0 con SQLite sin cifrar, desinstálala o borra sus datos antes de instalar 0.2.4; esa migración desde la base sin cifrar no está incluida.
- Si es una instalación nueva, inicia sin cuentas ni saldos. Si actualizas desde 0.2.0, conserva los datos existentes. Registra tus cuentas desde cero únicamente si estás instalando en un dispositivo nuevo.
- Guarda la contraseña de cada respaldo cifrado en un lugar seguro.

## Sincronización cloud

- Necesitas una URL HTTPS completa para un archivo remoto terminado en `.f360` en un servidor WebDAV compatible.
- Crea la carpeta remota previamente desde Nextcloud/NAS; la app sube el archivo, no crea la carpeta.
- Usa la misma contraseña de cifrado en los dispositivos que compartirán los datos.
- Para una primera sincronización en otro dispositivo sin datos, la app descargará la copia remota. Si ambos dispositivos ya tienen datos independientes, se conserva el archivo principal y se crea una copia de conflicto.

## IA online

- La IA local funciona sin API key.
- Para IA online debes introducir una API key de un proveedor compatible con OpenAI Chat Completions.
- El consumo de API se factura por separado del plan ChatGPT Plus.

## Importante

- El APK `debug` es para pruebas. No es una versión firmada para distribución pública.
- No subas contraseñas, API keys, archivos `.jks`, keystores ni datos personales al repositorio.
- Para distribuir una versión `release` firmada se debe crear un keystore y guardarlo en GitHub Secrets.


## Corrección de compilación 0.2.4
Si GitHub reportó que `okhttp-android:5.5.1` requiere `compileSdk 37`, esta versión ya cambia la dependencia a `com.squareup.okhttp3:okhttp:4.12.1`. Conserva `compileSdk 36` y el workflow con `android-actions/setup-android@v4`.


## Corrección biométrica 0.2.4
La actividad principal ahora hereda de `androidx.fragment.app.FragmentActivity`, requisito de AndroidX Biometric 1.1.0. Para aplicar el parche sin reemplazar el proyecto completo, sustituye `app/src/main/java/mx/finanzas360/app/MainActivity.kt` por el archivo incluido en esta versión.


## Corrección de arranque 0.2.4
Sustituye el proyecto completo por esta versión. Esta actualización corrige la apertura síncrona de SQLCipher en el hilo principal y declara el icono/splash screen. En caso de error, la app muestra el tipo y mensaje de excepción; Logcat registra la traza con `Finanzas360Startup`.

El workflow también actualiza `actions/upload-artifact` a v6 para retirar la advertencia de Node.js 20.
