@echo off
cd /d "%~dp0"
call mvn -U clean package
if errorlevel 1 pause
call mvn spring-boot:run
pause
