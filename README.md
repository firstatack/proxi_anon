# ProxiAnon

Tunel SSH para Android. Enruta **todo el trafico del telefono** a traves de un
servidor SSH propio (VPS), usando `VpnService` + `tun2socks`.

> Estado: **Fase 3** (VPN de todo el trafico, TCP). La app te conecta por
> usuario/contrasena, levanta un SOCKS5 local, y el modo VPN enruta TODO el
> trafico del telefono por el tunel (interfaz TUN + `tun2socks`). El UDP
> (QUIC/VoIP/dns nativo) llega en la Fase 4 con `badvpn-udpgw`.

## Arquitectura

```
[ apps del telefono ]
        |
   VpnService (TUN)
        |
   tun2socks  (IP <-> SOCKS5/UDP)
        |
   cliente SSH  --- canal cifrado --->  VPS (sshd)
                                          |
                                     SOCKS5 (TCP) + udpgw (UDP)
                                          |
                                       Internet
```

La app se excluye a si misma del VPN para evitar un bucle de enrutado.

## Modos de tunel

| Modo | TCP | DNS | UDP (QUIC/VoIP/juegos) | Requiere en el VPS |
|------|:---:|:---:|:---:|--------------------|
| `TCP` | si | forzado a TCP | no | solo `sshd` |
| `TCP + UDP` (recomendado) | si | nativo | si | `sshd` + `badvpn-udpgw` |

## Compilacion (en la nube, sin Android Studio)

El APK se compila en **GitHub Actions**. No necesitas Android Studio local.

1. Crea un repo en GitHub y sube este proyecto:

   ```bash
   cd proxi_anon
   git init && git add -A && git commit -m "ProxiAnon: skeleton + CI"
   git branch -M main
   git remote add origin https://github.com/<tu-usuario>/proxi_anon.git
   git push -u origin main
   ```

2. Ve a la pestana **Actions** del repo -> workflow **build-apk**.
3. Descarga el artifact **ProxiAnon-debug** (contiene `app-debug.apk`).
4. Pasalo al telefono, habilita "instalar apps de origen desconocido" e instalalo.

## Servidor (Fase 0)

En un VPS Ubuntu:

```bash
sudo TUNNEL_PASSWORD='una-contrasena-larga' ./scripts/vps-setup.sh
```

Crea el usuario, habilita contrasena + forwarding en `sshd`, instala
`badvpn-udpgw` (UDP sobre TCP) y `fail2ban`.

## Notas: MINA SSHD en Android (`ExceptionInInitializerError`)

MINA SSHD no soporta Android de forma oficial (solo ofrece "hooks"). Con
`sshd-core >= 2.14` la inicializacion estatica (`ECCurves.<clinit>`,
`BuiltinSignatures.<clinit>`) resuelve entidades de seguridad de forma eager y,
si falla en los providers de Android, `ExceptionUtils.peelException()` referencia
`javax.management.*` (inexistente en Android) -> `NoClassDefFoundError` ->
`ExceptionInInitializerError` al conectar. La app compila porque el problema es
de runtime (class loading perezoso, no de bytecode).

La solucion aplicada en este repo (`ProxiAnonApp`):

1. **BouncyCastle completo** (`bcprov-jdk18on`) reemplaza al "BC" interno de
   Android (recortado): `Security.removeProvider("BC")` +
   `Security.addProvider(BouncyCastleProvider())` en `onCreate`.
2. **Hooks de Android de MINA**: `OsUtils.setAndroid(true)` + valores para
   `user.home` / `user.dir` / `user.name` (null en Android y pueden romper
   codigo perezoso de sshd). Ver `docs/android.md` de MINA.

Si vuelve a fallar, el log de la app ahora muestra la **cadena completa de
causas** (antes se mostraba solo el `message`, que para
`ExceptionInInitializerError` es null): `ExceptionInInitializerError <-
NoClassDefFoundError: javax/management/... <- ...`

## Roadmap

- [x] **Fase 1** - esqueleto + CI que compila el APK
- [x] **Fase 2** - login SSH real + SOCKS5 local (MINA SSHD) + prueba HTTP in-app
- [x] **Fase 3** - `VpnService` + `tun2socks` (TODO el trafico, TCP) + DNS sin fugas
- [ ] **Fase 4** - UDP/udpgw, DNS nativo, kill-switch, IPv6, MTU
- [ ] **Fase 5** - UI final, firma de release, documentacion

### Fase 3 - como funciona

```
[apps] TCP ------------> 0.0.0.0/0 -> interfaz TUN -> la app lee los paquetes
                                |                      -> tun2socks (socketpair)
                                |                      |
                                |        [SOCKS5 local 127.0.0.1]
                                |                      |
                                +-> SshTunnel (MINA) -> VPS -> Internet
[apps] DNS (UDP:53) -> se intercepta en la app -> DNS over TCP via SOCKS -> 1.1.1.1
```

- `tun2socks` (Go/gvisor) se compila **como libreria nativa `libtun2socks.so`
  (buildmode c-shared)** en el CI y viaja en `app/src/main/jniLibs/<abi>/` (las 3
  ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`). Se carga con `System.loadLibrary`
  (dlopen): **ejecutar binarios en `/data/user/0` esta bloqueado por SELinux
  desde Android 10** (`error=13 Permission denied`), asi que no se ejecuta ningun
  proceso externo.
- El wrapper JNI esta en `native/libtun2socks/main.go`: crea un socketpair
  `SOCK_DGRAM` (un extremo queda en Go como device `fd://`, el otro va a Java) y
  arranca `engine.Start()` en proceso. La app bombea TUN<->socketpair e
  intercepta el DNS en el camino.
- La app se excluye del VPN (`addDisallowedApplication`): el socket SSH y el DNS
  proxy salen directo, evitando el bucle sin necesidad de `protect()` sobre el
  canal NIO2 de MINA.
- DNS sin fugas en modo TCP: un proxy local en `127.0.0.1:53` reenvia cada
  consulta por DNS-over-TCP a 1.1.1.1 **a traves del SOCKS5** (sale por el VPS).
- En MIUI/Xiaomi: activa el autostart y quita la restriccion de bateria de
  ProxiAnon, o el servicio de VPN puede morir en segundo plano.

## Aviso legal

Bypassear el control de acceso de una red puede violar sus terminos de servicio
y la normativa local. Usa esto solo en tus propios dispositivos y con permiso
sobre la red.
