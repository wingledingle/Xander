#!/usr/bin/env bash
# Starts local SMB, SFTP, FTP, FTPS, WebDAV and HTTP servers for the network integration tests
# (app/src/test/.../net/*IntegrationTest.kt). Requires root, samba, openssh-server, python3 with
# pyftpdlib, pyopenssl, wsgidav and cheroot. Run the tests with XANDER_IT=1.
set -uo pipefail
# Stop servers from a previous run.
for p in /tmp/xander-it/run/*.pid; do [ -f "$p" ] && kill "$(cat "$p")" 2>/dev/null; done
ROOT=${XANDER_IT_ROOT:-/tmp/xander-it}
mkdir -p "$ROOT"/{smb,sftp,ftp,dav,http,run}
chmod 777 "$ROOT"/{smb,sftp,ftp,dav,http}

# --- SMB (Samba): share "test", user xander / xanderpw ---
cat > "$ROOT/run/smb.conf" <<CONF
[global]
  server role = standalone server
  map to guest = Bad User
  smb ports = 4455
  pid directory = $ROOT/run
  lock directory = $ROOT/run
  state directory = $ROOT/run
  cache directory = $ROOT/run
  private dir = $ROOT/run
  ncalrpc dir = $ROOT/run/ncalrpc
  log file = $ROOT/run/smb.log
  server min protocol = SMB2_02
[test]
  path = $ROOT/smb
  read only = no
  guest ok = yes
  force user = root
CONF
id xander >/dev/null 2>&1 || useradd -m xander
mkdir -p /home/xander && chown xander /home/xander
echo "xander:xanderpw" | chpasswd
(echo xanderpw; echo xanderpw) | smbpasswd -c "$ROOT/run/smb.conf" -s -a xander >/dev/null

mkdir -p /run/samba "$ROOT/run/ncalrpc"
smbd -s "$ROOT/run/smb.conf" -D --no-process-group

# --- SFTP (OpenSSH) on 2222: user xander / xanderpw ---
mkdir -p /run/sshd
[ -f "$ROOT/run/ssh_host_ed25519_key" ] || ssh-keygen -q -t ed25519 -N "" -f "$ROOT/run/ssh_host_ed25519_key"
cat > "$ROOT/run/sshd_config" <<CONF
Port 2222
HostKey $ROOT/run/ssh_host_ed25519_key
PasswordAuthentication yes
PubkeyAuthentication yes
UsePAM yes
Subsystem sftp internal-sftp
PidFile $ROOT/run/sshd.pid
CONF

/usr/sbin/sshd -f "$ROOT/run/sshd_config"
chown xander "$ROOT/sftp"

# --- FTP on 2121 (pyftpdlib) and explicit FTPS on 2990 (vsftpd): user xander / xanderpw ---
[ -f "$ROOT/run/ftps.pem" ] || openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=localhost" -keyout "$ROOT/run/ftps.pem" -out "$ROOT/run/ftps.pem" 2>/dev/null
cat > "$ROOT/run/ftp.py" <<PY
import threading
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler, TLS_FTPHandler
from pyftpdlib.servers import FTPServer
a = DummyAuthorizer(); a.add_user("xander", "xanderpw", "$ROOT/ftp", perm="elradfmwMT"); a.add_anonymous("$ROOT/ftp")
h = FTPHandler; h.authorizer = a; h.passive_ports = range(30000, 30100)
class T(TLS_FTPHandler): pass
T.authorizer = a; T.certfile = "$ROOT/run/ftps.pem"; T.passive_ports = range(30100, 30200)
s1 = FTPServer(("127.0.0.1", 2121), h)
s1.serve_forever()
PY

nohup python3 "$ROOT/run/ftp.py" > "$ROOT/run/ftp.log" 2>&1 &
echo $! > "$ROOT/run/ftp.pid"

mkdir -p "$ROOT/run/vsftpd-empty"
cat > "$ROOT/run/vsftpd.conf" <<CONF
listen=YES
listen_address=127.0.0.1
listen_port=2990
local_enable=YES
write_enable=YES
local_umask=022
anonymous_enable=NO
pasv_enable=YES
pasv_min_port=30200
pasv_max_port=30299
ssl_enable=YES
force_local_logins_ssl=YES
force_local_data_ssl=YES
require_ssl_reuse=NO
rsa_cert_file=$ROOT/run/ftps.pem
rsa_private_key_file=$ROOT/run/ftps.pem
secure_chroot_dir=$ROOT/run/vsftpd-empty
seccomp_sandbox=NO
pam_service_name=vsftpd
background=YES
CONF
/usr/sbin/vsftpd "$ROOT/run/vsftpd.conf"

# --- WebDAV on 8088 (wsgidav, basic auth xander / xanderpw) ---

cat > "$ROOT/run/dav.yaml" <<YAML
host: 127.0.0.1
port: 8088
provider_mapping:
  "/": "$ROOT/dav"
http_authenticator:
  domain_controller: null
  accept_basic: true
  accept_digest: true
  default_to_digest: false
simple_dc:
  user_mapping:
    "*":
      "xander":
        password: "xanderpw"
verbose: 1
YAML

nohup wsgidav -c "$ROOT/run/dav.yaml" > "$ROOT/run/dav.log" 2>&1 &
echo $! > "$ROOT/run/dav.pid"

# --- HTTP directory listing on 8099 ---

mkdir -p "$ROOT/http/sub"; echo "hello http" > "$ROOT/http/sub/hello.txt"; echo "root file" > "$ROOT/http/readme.txt"
(cd "$ROOT/http" && nohup python3 -m http.server 8099 --bind 127.0.0.1 > "$ROOT/run/http.log" 2>&1 & echo $! > "$ROOT/run/http.pid")

sleep 2
echo "Test servers started under $ROOT"
