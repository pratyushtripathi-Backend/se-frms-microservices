# Local Kafka recovery

Kafka was copied from Windows into Ubuntu-24.04 WSL. The original Windows broker was stopped. All 384 data files were SHA-256 verified before the Linux broker started. No storage formatting or offset reset was performed.

- Original backup: `C:\tmp\kraft-combined-logs` (leave intact).
- Active Linux data: `/var/lib/frms-kafka`.
- Kafka installation: `/opt/frms-kafka` (same binaries/version as the Windows installation).
- Linux service: `frms-kafka`, enabled on Ubuntu startup.
- Broker endpoint for existing Windows applications: `localhost:9092`.
- Cluster ID retained: `QDiQAZoVQoO5zA7HZV-PKw`.

Do not start the old Windows broker alongside the Linux broker. The Windows data is a point-in-time backup, not a synchronized replica.

After Windows restarts, double-click `start-infrastructure.cmd` in this directory and keep its window open. It starts Kafka and Redis and keeps WSL alive without requiring PowerShell script execution policy changes.

Diagnostics from PowerShell:

```powershell
wsl -d Ubuntu-24.04 -u root --exec systemctl status frms-kafka --no-pager
wsl -d Ubuntu-24.04 -u root --exec journalctl -u frms-kafka -n 50 --no-pager
```

Recovery verification: Kafka topic metadata was read successfully from both Linux and Windows on 2026-10-15. Gateway and transaction Actuator health were UP. No new test transactions were submitted for this recovery.

The recovered `frms.transactions` topic originally had one partition. After the baseline, explicit user approval was obtained and it was expanded to **12 partitions** on 2026-10-15. Verify consumer assignments after metadata refresh/restart before the next concurrency benchmark.

The migration script is a one-time tool and intentionally refuses to overwrite an existing Linux installation/data directory. Do not rerun it to start services.
