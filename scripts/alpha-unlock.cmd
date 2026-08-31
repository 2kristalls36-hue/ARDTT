@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
where python >nul 2>&1 && python alpha-unlock.py %* && exit /b %ERRORLEVEL%
where py >nul 2>&1 && py -3 alpha-unlock.py %* && exit /b %ERRORLEVEL%
echo Нужен Python 3: https://www.python.org/downloads/
pause
exit /b 1
