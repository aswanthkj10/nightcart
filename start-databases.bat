@echo off
cd /d "%~dp0"
docker compose up -d
if errorlevel 1 pause

docker compose ps -a
pause
