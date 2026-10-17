#!/usr/bin/env bash
set -euo pipefail
test ! -e /opt/frms-kafka
test ! -e /var/lib/frms-kafka
test -f /mnt/c/tmp/kraft-combined-logs/meta.properties
mkdir /opt/frms-kafka
cp -a /mnt/c/kafka/bin /mnt/c/kafka/libs /mnt/c/kafka/config /opt/frms-kafka/
python3 - <<'PY'
import hashlib, pathlib, shutil
src = pathlib.Path('/mnt/c/tmp/kraft-combined-logs')
dst = pathlib.Path('/var/lib/frms-kafka')
shutil.copytree(src, dst)
count = 0
for original in src.rglob('*'):
    if original.is_file():
        copied = dst / original.relative_to(src)
        def digest(p):
            with p.open('rb') as f:
                return hashlib.file_digest(f, 'sha256').digest()
        if digest(original) != digest(copied):
            raise RuntimeError(f'Copy mismatch: {original}')
        count += 1
print(f'Verified {count} copied Kafka files with SHA-256; Windows source preserved.')
PY
sed -i 's|^log.dirs=.*|log.dirs=/var/lib/frms-kafka|' /opt/frms-kafka/config/server.properties
sed -i 's|^listeners=.*|listeners=PLAINTEXT://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093|' /opt/frms-kafka/config/server.properties
chmod +x /opt/frms-kafka/bin/*.sh
id frms-kafka >/dev/null 2>&1 || useradd --system --home-dir /var/lib/frms-kafka --shell /usr/sbin/nologin frms-kafka
mkdir -p /opt/frms-kafka/logs
chown -R frms-kafka:frms-kafka /var/lib/frms-kafka /opt/frms-kafka/logs
chmod -R u+rwX /var/lib/frms-kafka
cat > /etc/systemd/system/frms-kafka.service <<'UNIT'
[Unit]
Description=FRMS local Kafka broker
After=network.target

[Service]
Type=simple
User=frms-kafka
WorkingDirectory=/opt/frms-kafka
Environment="KAFKA_HEAP_OPTS=-Xms512m -Xmx1g"
Environment="JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true"
ExecStart=/opt/frms-kafka/bin/kafka-server-start.sh /opt/frms-kafka/config/server.properties
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
LimitNOFILE=100000

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now frms-kafka
