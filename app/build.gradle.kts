plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.proxianon.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.proxianon.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Varios jars (MINA, slf4j) traen ficheros META-INF duplicados que rompen el merge.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.kotlinx.coroutines.android)

    // SSH (Apache MINA SSHD, Java 8 bytecode -> compatible con Android)
    implementation(libs.mina.sshd)
    // Soporte de host keys ed25519 (lo que usa OpenSSH por defecto)
    implementation(libs.eddsa)
    // BouncyCastle completo: MINA SSHD 2.18 resuelve las entidades de seguridad
    // (EC, ECDSA, ed25519, etc.) durante la inicializacion estatica, y el BC
    // interno/recortado de Android no basta -> sin esto, al conectar lanza
    // ExceptionInInitializerError (NoClassDefFoundError: javax/management.*)
    // dentro de ExceptionUtils.peelException(). Se registra en ProxiAnonApp.
    implementation(libs.bouncycastle)
    // Almacen cifrado de perfiles SSH (clave maestra en Android Keystore).
    implementation(libs.security.crypto)
    runtimeOnly(libs.slf4j.nop)

    debugImplementation(libs.androidx.ui.tooling)
}
