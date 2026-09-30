package com.proxianon.app

import android.app.Application
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Inicializacion de la app.
 *
 * MINA SSHD 2.18 no soporta Android de forma oficial (solo incluye "hooks", ver
 * docs/android.md del proyecto). Hay dos ajustes obligatorios para que la
 * conexion no reviente con ExceptionInInitializerError:
 *
 * 1) BouncyCastle completo como provider "BC".
 *    El BC que trae Android (com.android.org.bouncycastle) es una version
 *    recortada y no cubre las entidades de seguridad que sshd resuelve durante
 *    la inicializacion estatica (ECCurves.<clinit> etc.). Cuando esa resolucion
 *    falla, sshd llama a ExceptionUtils.peelException() que referencia
 *    javax.management.* (paquete que NO existe en Android) y termina en
 *    NoClassDefFoundError -> ExceptionInInitializerError al conectar.
 *    Solucion: quitar el "BC" interno y registrar bcprov-jdk18on completo
 *    (verificado: con este provider registrado el flujo completo KEX + ed25519
 *    + SOCKS5 funciona sin los providers de una JVM).
 *
 * 2) Hooks de Android de MINA (user.home / user.dir / user.name).
 *    En Android esos system properties devuelven null y el codigo perezoso de
 *    sshd (PathUtils, OsUtils, KeyUtils) puede romperse al leerlos.
 */
class ProxiAnonApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // --- Hooks de Android de MINA SSHD (docs/android.md) ---
        runCatching {
            OsUtils.setAndroid(true)
            System.setProperty("user.name", "proxianon")
            OsUtils.setCurrentUser("proxianon")

            val base = filesDir
            System.setProperty("user.home", base.absolutePath)
            System.setProperty("user.dir", base.absolutePath)
            PathUtils.setUserHomeFolderResolver { base.toPath() }
            OsUtils.setCurrentWorkingDirectoryResolver { base.toPath() }
        }

        // --- BouncyCastle completo como provider "BC" ---
        runCatching {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
    }
}