#!/usr/bin/env bash
#
# ProxiAnon - Fase 0: prepara un VPS Ubuntu como servidor de tunel SSH.
#
# Que hace:
#   1. Crea un usuario dedicado (por defecto "tunel") con contrasena.
#   2. Deja su shell en nologin (solo forwarding, sin terminal interactiva).
#   3. Habilita autenticacion por contrasena + TCP forwarding en sshd.
#   4. Instala y arranca badvpn-udpgw (gateway UDP-sobre-TCP) en 127.0.0.1.
#   5. Instala fail2ban (mitiga el bruteforce que habilita la contrasena).
#
# Uso:
#   sudo ./vps-setup.sh
#   sudo TUNNEL_USER=vpn TUNNEL_PASSWORD='...' SSH_PORT=443 ./vps-setup.sh
#
# Variables:
#   TUNNEL_USER      nombre del usuario de tunel        (def: tunel)
#   TUNNEL_PASSWORD  contrasena; si vacio, la pide      (def: pide)
#   SSH_PORT         puerto de sshd (443 evade DPI)     (def: 22)
#   UDPGW_PORT       puerto local de badvpn-udpgw       (def: 7300)
#
set -euo pipefail

TUNNEL_USER="${TUNNEL_USER:-tunel}"
TUNNEL_PASSWORD="${TUNNEL_PASSWORD:-}"
SSH_PORT="${SSH_PORT:-22}"
UDPGW_PORT="${UDPGW_PORT:-7300}"

log()  { printf '\033[1;36m[ProxiAnon]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[aviso]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Ejecuta este script como root (sudo)."

# ---------------------------------------------------------------------------
# 1. Usuario de tunel
# ---------------------------------------------------------------------------
if id "$TUNNEL_USER" >/dev/null 2>&1; then
    log "El usuario '$TUNNEL_USER' ya existe, lo reutilizo."
else
    log "Creando usuario '$TUNNEL_USER'..."
    if [[ -n "$TUNNEL_PASSWORD" ]]; then
        useradd -m -s /usr/sbin/nologin "$TUNNEL_USER"
        echo "${TUNNEL_USER}:${TUNNEL_PASSWORD}" | chpasswd
    else
        # adduser pide la contrasena interactivamente
        adduser --shell /usr/sbin/nologin "$TUNNEL_USER"
    fi
fi

if [[ -n "$TUNNEL_PASSWORD" ]]; then
    log "Asignando contrasena al usuario '$TUNNEL_USER'..."
    echo "${TUNNEL_USER}:${TUNNEL_PASSWORD}" | chpasswd
fi

command -v nologin >/dev/null 2>&1 && grep -qx '/usr/sbin/nologin' /etc/shells \
    || echo '/usr/sbin/nologin' >> /etc/shells

# ---------------------------------------------------------------------------
# 2. Configuracion de sshd (drop-in)
# ---------------------------------------------------------------------------
log "Escribiendo configuracion de sshd..."
install -d -m 0755 /etc/ssh/sshd_config.d
SSHD_DROPIN=/etc/ssh/sshd_config.d/00-proxianon.conf
cat > "$SSHD_DROPIN" <<EOF
# ProxiAnon - generado por vps-setup.sh
Port ${SSH_PORT}
PasswordAuthentication yes
KbdInteractiveAuthentication yes
PermitEmptyPasswords no
AllowTcpForwarding yes
AllowAgentForwarding no
X11Forwarding no
PermitTunnel no
GatewayPorts no
ClientAliveInterval 30
ClientAliveCountMax 3
EOF

# Asegura que sshd_config incluye el directorio de drop-ins.
if ! grep -qE '^\s*Include\s+/etc/ssh/sshd_config\.d/\*\.conf' /etc/ssh/sshd_config; then
    warn "Tu /etc/ssh/sshd_config no incluye sshd_config.d; anado el Include al inicio."
    sed -i '1i Include /etc/ssh/sshd_config.d/*.conf' /etc/ssh/sshd_config
fi

log "Validando configuracion de sshd..."
sshd -t || die "La configuracion de sshd es invalida. No reinicio para no cortarte el acceso."

systemctl restart ssh 2>/dev/null || systemctl restart sshd
log "sshd reiniciado (puerto ${SSH_PORT})."

if [[ "$SSH_PORT" != "22" ]]; then
    warn "Cambiaste el puerto a ${SSH_PORT}: abre ese puerto en el firewall/security group del VPS."
fi

# ---------------------------------------------------------------------------
# 3. badvpn-udpgw (UDP sobre TCP)
# ---------------------------------------------------------------------------
if command -v badvpn-udpgw >/dev/null 2>&1; then
    log "badvpn-udpgw ya esta instalado."
else
    if apt-get install -y badvpn >/dev/null 2>&1 && command -v badvpn-udpgw >/dev/null 2>&1; then
        log "badvpn-udpgw instalado desde apt."
    else
        log "badvpn-udpgw no esta en los repos; compilando desde fuente..."
        apt-get update -y
        apt-get install -y cmake build-essential git
        BUILD_DIR="$(mktemp -d)"
        git clone --depth 1 https://github.com/ambrop72/badvpn.git "$BUILD_DIR/badvpn"
        mkdir -p "$BUILD_DIR/badvpn/build"
        (
            cd "$BUILD_DIR/badvpn/build"
            cmake .. -DBUILD_NOTHING_BY_DEFAULT=1 -DBUILD_UDPGW=1
            make -j"$(nproc)"
            install -m 0755 udpgw /usr/local/bin/badvpn-udpgw
        )
        rm -rf "$BUILD_DIR"
        log "badvpn-udpgw instalado en /usr/local/bin."
    fi
fi

log "Creando servicio systemd de badvpn-udpgw..."
cat > /etc/systemd/system/badvpn-udpgw.service <<EOF
[Unit]
Description=ProxiAnon badvpn udpgw (gateway UDP sobre TCP)
After=network.target

[Service]
ExecStart=/usr/local/bin/badvpn-udpgw --listen-addr 127.0.0.1:${UDPGW_PORT} --max-clients 100 --max-connections-for-client 16 --loglevel warning
Restart=always
RestartSec=2
User=nobody
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now badvpn-udpgw

# ---------------------------------------------------------------------------
# 4. fail2ban
# ---------------------------------------------------------------------------
if ! command -v fail2ban-client >/dev/null 2>&1; then
    log "Instalando fail2ban..."
    apt-get install -y fail2ban
fi
systemctl enable --now fail2ban 2>/dev/null || true

# ---------------------------------------------------------------------------
# Resumen
# ---------------------------------------------------------------------------
IP="$(hostname -I | awk '{print $1}')"
cat <<EOF

============================================================================
 ProxiAnon - VPS listo
============================================================================
  Host            : ${IP}
  Puerto SSH      : ${SSH_PORT}
  Usuario         : ${TUNNEL_USER}
  Contrasena      : (la que definiste)

  Modo TCP        : ssh -N -D 1080 ${TUNNEL_USER}@${IP} -p ${SSH_PORT}
  Modo TCP+UDP    : ssh -N -D 1080 -L ${UDPGW_PORT}:127.0.0.1:${UDPGW_PORT} \\
                        ${TUNNEL_USER}@${IP} -p ${SSH_PORT}

  udpgw escucha en 127.0.0.1:${UDPGW_PORT} (no expuesto a Internet).
============================================================================
EOF
