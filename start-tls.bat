@echo off
rem Start the container with conf/server-tls.xml (HTTPS + HTTP/3 on 8443).
rem Run scripts\dev-tls-setup.bat first.

setlocal

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

if not defined GUMDROP_HOME (
	if exist "%SCRIPT_DIR%\dist\container-home\bin\gumdrop.bat" (
		set "GUMDROP_HOME=%SCRIPT_DIR%\dist\container-home"
	) else if exist "%SCRIPT_DIR%\bin\gumdrop.bat" (
		set "GUMDROP_HOME=%SCRIPT_DIR%"
	)
)

if not defined GUMDROP_HOME (
	echo start-tls: run ant assemble-container first.
	exit /b 1
)
if not exist "%GUMDROP_HOME%\bin\gumdrop.bat" (
	echo start-tls: missing %GUMDROP_HOME%\bin\gumdrop.bat
	exit /b 1
)
if not exist "%GUMDROP_HOME%\conf\server-tls.xml" (
	echo start-tls: missing conf\server-tls.xml — re-run ant assemble-container.
	exit /b 1
)
if not exist "%GUMDROP_HOME%\conf\keystore.p12" (
	echo start-tls: missing keystore — run scripts\dev-tls-setup.bat first.
	exit /b 1
)

call "%GUMDROP_HOME%\bin\gumdrop.bat" "%GUMDROP_HOME%\conf\server-tls.xml" %*
exit /b %ERRORLEVEL%
