# ProxiAnon

Tunel SSH para Android. Enruta **todo el trafico del telefono** a traves de un
servidor SSH propio (VPS), usando `VpnService` + `tun2socks`.

> Estado: **estable (v0.5)**. Todo el trafico TCP sale por tu VPS, DNS sin fugas,
> perfiles SSH guardados cifrados, reconexion automatica, verificacion de host
> key (anti-MITM), estadisticas de trafico y APK release firmado por GitHub
> Actions. El soporte UDP (QUIC/VoIP/juegos) queda pendiente (TCP-only por ahora).

## Arquitectura

```
[ apps del telefono ]
        |
   VpnService (TUN 0.0.0.0/0)
        |
   la app lee los paquetes -> tun2socks (gvisor, in-process) -> SOCKS5 local
        |                                                          |
   DNS (UDP:53) interceptado -> DNS over TCP via SOCKS             |
        |                                                          v
   SshTunnel (MINA SSHD, host key verificada) <------------ 127.0.0.1:puerto
        |
      VPS (sshd) --> Internet (IP de salida = IP del VPS)
```

- La app se excluye del VPN (`addDisallowedApplication`) para evitar el bucle;
  el socket SSH y el DNS salen directo. Mientras se reconecta, el TUN sigue vivo
  y los paquetes se descartan (kill-switch implicito: no hay fuga).

## Funcionalidades

| Funcion | Como |
|---|---|
| **Todo el trafico por el VPS** | `VpnService` + `tun2socks` + SSH (TCP) |
| **DNS sin fugas** | Intercepcion de `UDP:53` en la app -> DNS over TCP a 1.1.1.1 via el tunel |
| **Perfiles SSH** | Multiples cuentas guardadas **cifradas** (EncryptedSharedPreferences) con selector y CRUD |
| **Host key verificada (anti-MITM)** | TOFU: primera conexion guarda la huella; si cambia, falla con aviso |
| **Reconexion automatica** | Monitor + `ConnectivityManager` + backoff (1s->2s->5s->15s->30s->60s) sin recrear el TUN |
| **Persistencia** | Si el proceso muere, `START_STICKY` reanuda con las credenciales cifradas |
| **Estadisticas** | TX / RX / Total (formateados) + tiempo de sesion en la UI |
| **Notificacion en vivo** | Muestra `↓/↑` de trafico, actualizada cada 5 s |
| **IPv4-only** | Por ahora solo TCP/IPv4; IPv6 y UDP quedan para futuras versiones |

## Uso

1. Entra a **Cuentas** -> **Añadir** tu VPS (nombre, host, puerto, usuario, contrasena).
2. **Usar** el perfil (o rellena el formulario directamente).
3. Pulsa **Conectar** (tunel manual + SOCKS5 + prueba de IP) o **Activar VPN**
   (todo el trafico del telefono por el tunel).
4. Con VPN activo veras la linea de estadisticas (`↓ entrada · ↑ salida · Σ total · ⏱ tiempo`).
5. La primera conexion guarda la huella SSH del servidor; si alguna vez cambia,
   la app avisa de un posible MITM.

## Instalar

Descarga el APK del **GitHub Release** mas reciente (o del artifact
`ProxiAnon-debug` de Actions) y pide "instalar apps de origen desconocido".

- `app-release.apk`: firmado con la clave de release (para uso normal).
- `app-debug.apk`: para pruebas rapidas.

> En MIUI/Xiaomi (y si el tunel se corta solo en segundo plano): activa el
> **autostart** y quita la **restriccion de bateria** de ProxiAnon en
> Ajustes > Bateria.

## Servidor (la primera vez, en el VPS)

```bash
sudo TUNNEL_PASSWORD='una-contrasena-larga' ./scripts/vps-setup.sh
```

Crea el usuario, habilita contrasena + forwarding en `sshd`, instala
`badvpn-udpgw` (pendiente de integrar: UDP) y `fail2ban`.

## Compilacion y release (GitHub Actions)

- **`build-apk`** (push a `main`): compila `tun2socks` (Go, c-shared) para las
  3 ABIs, ensambla `assembleDebug` y `assembleRelease`.
- **`release-apk`** (tag `v*`): compila y firma `assembleRelease` con el keystore
  de los **Secrets** y publica el APK como **GitHub Release**.

### Firma de release (una sola vez)

1. `keytool -genkeypair -v -keystore proxianon-release.jks -alias proxianon -keyalg RSA -keysize 2048 -validity 10000 ...`
2. Subir en GitHub -> **Settings -> Secrets and variables -> Actions**:
   `PROXIANON_KEYSTORE_B64` (el .jks en base64), `PROXIANON_KEYSTORE_PASSWORD`,
   `PROXIANON_KEY_ALIAS`, `PROXIANON_KEY_PASSWORD`.
3. Para lanzar una release: `git tag vX.Y.Z && git push origin vX.Y.Z` (el tag
   debe apuntar al commit mas reciente de `main`). Detalle en `MANUAL_RELEASE.html`.

> **Seguridad**: el `.jks` y las contrasenas **nunca** van al repo (ver
> `.gitignore`); guarda copia del keystore y la contrasena en un lugar seguro,
> o no podras actualizar la app instalada.

## Notas tecnicas (por que esta montado asi)

### MINA SSHD en Android (`ExceptionInInitializerError`)
MINA no soporta Android oficialmente: con `sshd-core >= 2.14` la inicializacion
estatica (`ECCurves.<clinit>`) resuelve entidades de seguridad y, al fallar en
Android, `ExceptionUtils.peelException()` referencia `javax.management.*`
(inexistente) -> `NoClassDefFoundError` -> `ExceptionInInitializerError`.
Solucion (`ProxiAnonApp`): **BouncyCastle completo** (`bcprov-jdk18on`) como
provider `BC` + hooks de Android (`OsUtils.setAndroid`, resolvers de `user.home`).

### tun2socks como libreria nativa (`libtun2socks.so`)
Desde Android 10, SELinux **bloquea ejecutar binarios** en `/data/user/0`
(`error=13 Permission denied`). Por eso tun2socks se compila como
`-buildmode=c-shared` y se carga con `System.loadLibrary` (dlopen). El wrapper
JNI (`native/libtun2socks/main.go`) crea un socketpair `SOCK_DGRAM` (un extremo
queda en Go como device `fd://`, el otro va a Java) y la app bombea
TUN<->socketpair interceptando el DNS en el camino.

## Roadmap (pendiente)

- [x] **Fase 1-3** - esqueleto+CI, SSH+SOCKS5, VPN completo con DNS sin fugas
- [x] **Robustez** - reconexion, persistencia, host key, stats
- [x] **Perfiles + UI + firma release + GitHub Releases**
- [ ] **Fase 4** - UDP (udpgw), IPv6, kill-switch real, historial por sesion
- [ ] **Extras** - Quick Settings tile, selector de apps fuera del tunel, exportar log

## Aviso legal

Bypassear el control de acceso de una red puede violar sus terminos de servicio
y la normativa local. Usa esto solo en tus propios dispositivos y con permiso
sobre la red.