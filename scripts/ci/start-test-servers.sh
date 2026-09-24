#!/usr/bin/env bash
# Starts REAL servers on this Ubuntu machine, used by the integration tests (on the computer and in
# the Android emulator, which reaches them at 10.0.2.2):
#
#   OpenSSH  SFTP              port 22     folder /srv/scrigno
#   vsftpd   FTP + FTPS (TLS)  port 21     folder /srv/scrigno   (self-signed certificate)
#   Samba    SMB 2/3           port 445    share "photos" = /srv/scrigno
#   Apache   WebDAV            port 8080   URL path /dav
#   Apache   WebDAV over TLS   port 8443   URL path /dav         (self-signed certificate)
#
# One user for all of them: $SCRIGNO_TEST_USER / $SCRIGNO_TEST_PASSWORD (defaults below).
# Works with systemd (CI) and without it (containers). Needs root or sudo.
set -euo pipefail

USER_NAME="${SCRIGNO_TEST_USER:-scrigno}"
PASSWORD="${SCRIGNO_TEST_PASSWORD:-Scr1gno-test-pw}"
ROOT=/srv/scrigno
DAV_ROOT=/srv/scrigno-dav
SUDO=""
[ "$(id -u)" -eq 0 ] || SUDO="sudo"

log() { printf '\n=== %s\n' "$*"; }
has_systemd() { [ -d /run/systemd/system ]; }

log "Installing OpenSSH, vsftpd, Samba, Apache"
$SUDO apt-get update -qq || true
$SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    openssh-server vsftpd samba apache2 apache2-utils ssl-cert >/dev/null

log "User $USER_NAME and folders"
id "$USER_NAME" >/dev/null 2>&1 || $SUDO useradd -m -s /bin/bash "$USER_NAME"
echo "$USER_NAME:$PASSWORD" | $SUDO chpasswd
$SUDO mkdir -p "$ROOT" "$DAV_ROOT" /var/lib/apache2/dav
$SUDO chown -R "$USER_NAME:$USER_NAME" "$ROOT"
$SUDO chown -R www-data:www-data "$DAV_ROOT" /var/lib/apache2/dav
$SUDO make-ssl-cert generate-default-snakeoil --force-overwrite

log "OpenSSH (SFTP) on port 22"
$SUDO ssh-keygen -A >/dev/null
$SUDO mkdir -p /run/sshd
# "00-" so that it wins over cloud images that disable passwords in a later file.
printf 'PasswordAuthentication yes\nKbdInteractiveAuthentication yes\n' |
    $SUDO tee /etc/ssh/sshd_config.d/00-scrigno.conf >/dev/null
if has_systemd; then
    $SUDO systemctl restart ssh
else
    $SUDO pkill -x sshd || true
    $SUDO /usr/sbin/sshd
fi

log "vsftpd (FTP and explicit FTPS) on port 21"
$SUDO tee /etc/vsftpd.conf >/dev/null <<'EOF'
listen=YES
listen_ipv6=NO
anonymous_enable=NO
local_enable=YES
write_enable=YES
local_umask=022
connect_from_port_20=NO
secure_chroot_dir=/var/run/vsftpd/empty
pam_service_name=vsftpd
pasv_enable=YES
pasv_min_port=40000
pasv_max_port=40100
utf8_filesystem=YES
ssl_enable=YES
allow_anon_ssl=NO
force_local_data_ssl=NO
force_local_logins_ssl=NO
require_ssl_reuse=NO
rsa_cert_file=/etc/ssl/certs/ssl-cert-snakeoil.pem
rsa_private_key_file=/etc/ssl/private/ssl-cert-snakeoil.key
EOF
$SUDO mkdir -p /var/run/vsftpd/empty
if has_systemd; then
    $SUDO systemctl restart vsftpd
else
    $SUDO pkill -x vsftpd || true
    $SUDO sh -c 'nohup vsftpd /etc/vsftpd.conf >/var/log/vsftpd-scrigno.log 2>&1 &'
fi

log "Samba (SMB) on port 445, share \"photos\""
$SUDO tee /etc/samba/smb.conf >/dev/null <<EOF
[global]
   workgroup = WORKGROUP
   server role = standalone server
   map to guest = never
   server min protocol = SMB2_10
   smb ports = 445
   disable netbios = yes
   unix charset = UTF-8
   log file = /var/log/samba/log.%m

[photos]
   path = $ROOT
   valid users = $USER_NAME
   read only = no
   browseable = yes
   create mask = 0644
   directory mask = 0755
EOF
printf '%s\n%s\n' "$PASSWORD" "$PASSWORD" | $SUDO smbpasswd -a -s "$USER_NAME" >/dev/null
if has_systemd; then
    $SUDO systemctl restart smbd
else
    $SUDO pkill -x smbd || true
    $SUDO smbd -D
fi

log "Apache (WebDAV) on ports 8080 and 8443"
$SUDO a2enmod -q dav dav_fs ssl auth_basic authn_file alias >/dev/null
$SUDO htpasswd -bc /etc/apache2/scrigno.htpasswd "$USER_NAME" "$PASSWORD" 2>/dev/null
$SUDO tee /etc/apache2/sites-available/scrigno-dav.conf >/dev/null <<EOF
Listen 8080
Listen 8443
DavLockDB /var/lib/apache2/dav/lockdb

<Directory $DAV_ROOT>
    Options None
    AllowOverride None
    Require all granted
</Directory>

<VirtualHost *:8080>
    Alias /dav $DAV_ROOT
    <Location /dav>
        DAV On
        AuthType Basic
        AuthName "Scrigno"
        AuthUserFile /etc/apache2/scrigno.htpasswd
        Require valid-user
    </Location>
</VirtualHost>

<VirtualHost *:8443>
    SSLEngine on
    SSLCertificateFile /etc/ssl/certs/ssl-cert-snakeoil.pem
    SSLCertificateKeyFile /etc/ssl/private/ssl-cert-snakeoil.key
    Alias /dav $DAV_ROOT
    <Location /dav>
        DAV On
        AuthType Basic
        AuthName "Scrigno"
        AuthUserFile /etc/apache2/scrigno.htpasswd
        Require valid-user
    </Location>
</VirtualHost>
EOF
$SUDO a2ensite -q scrigno-dav >/dev/null
$SUDO apache2ctl configtest
if has_systemd; then
    $SUDO systemctl restart apache2
else
    $SUDO apache2ctl stop >/dev/null 2>&1 || true
    sleep 1
    $SUDO apache2ctl start
fi

log "Waiting for the ports"
for port in 21 22 445 8080 8443; do
    for _ in $(seq 1 30); do
        if (echo >"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then break; fi
        sleep 1
    done
    (echo >"/dev/tcp/127.0.0.1/$port") 2>/dev/null || { echo "Port $port is not open"; exit 1; }
    echo "port $port ok"
done

log "Test servers ready (user: $USER_NAME)"
