@echo off
echo Starting Redis and Kafka in Ubuntu. Keep this window open while testing.
wsl.exe -d Ubuntu-24.04 -u root --exec sh -c "systemctl start redis-server frms-kafka && redis-cli ping && systemctl is-active frms-kafka && exec sleep infinity"
