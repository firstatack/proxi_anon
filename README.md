# ProxiAnon

Tunel SSH para Android. Enruta **todo el trafico del telefono** a traves de un
servidor SSH propio (VPS), usando `VpnService` + `tun2socks`.

> Estado: **Fase 2** (conexion SSH + SOCKS5 local). La app se conecta al VPS con
> usuario/contrasena, levanta un proxy SOCKS5 local y verifica la IP de salida.
> El `VpnService` (todo el trafico) llega en la Fase 3.

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

## Roadmap

- [x] **Fase 1** - esqueleto + CI que compila el APK
- [x] **Fase 2** - login SSH real + SOCKS5 local (MINA SSHD) + prueba HTTP in-app
- [ ] **Fase 3** - `VpnService` + tun2socks nativo (todo el trafico)
- [ ] **Fase 4** - UDP/udpgw, DNS, kill-switch, IPv6, MTU
- [ ] **Fase 5** - UI final, firma de release, documentacion

## Aviso legal

Bypassear el control de acceso de una red puede violar sus terminos de servicio
y la normativa local. Usa esto solo en tus propios dispositivos y con permiso
sobre la red.
