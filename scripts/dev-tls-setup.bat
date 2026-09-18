@echo off
rem Create a locally trusted TLS keystore for Gumdrop (HTTPS + HTTP/3 on 8443).
rem Requires mkcert and openssl on PATH. See BUILDING.md.

setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
for %%I in ("%SCRIPT_DIR%\..") do set "REPO_ROOT=%%~fI"
set "ETC_DIR=%REPO_ROOT%\etc"
set "CERT_PEM=%ETC_DIR%\localhost.pem"
set "KEY_PEM=%ETC_DIR%\localhost-key.pem"
set "KEYSTORE=%ETC_DIR%\keystore.p12"
if not defined GUMDROP_DEV_TLS_STORE_PASS set "GUMDROP_DEV_TLS_STORE_PASS=changeit"

where mkcert >nul 2>&1
if errorlevel 1 (
	echo dev-tls-setup: mkcert not found. Install from https://github.com/FiloSottile/mkcert
	exit /b 1
)
where openssl >nul 2>&1
if errorlevel 1 (
	echo dev-tls-setup: openssl not found ^(Git for Windows or OpenSSL on PATH^).
	exit /b 1
)

if not exist "%ETC_DIR%" mkdir "%ETC_DIR%"

echo dev-tls-setup: installing local CA into system trust store...
mkcert -install
if errorlevel 1 exit /b 1

echo dev-tls-setup: generating localhost certificate in etc\...
mkcert -cert-file "%CERT_PEM%" -key-file "%KEY_PEM%" localhost ::1
if errorlevel 1 exit /b 1

echo dev-tls-setup: building PKCS#12 keystore...
openssl pkcs12 -export -in "%CERT_PEM%" -inkey "%KEY_PEM%" -out "%KEYSTORE%" -passout pass:%GUMDROP_DEV_TLS_STORE_PASS% -name gumdrop
if errorlevel 1 exit /b 1

set "CONTAINER_CONF=%REPO_ROOT%\dist\container-home\conf"
if exist "%CONTAINER_CONF%" (
	copy /Y "%KEYSTORE%" "%CONTAINER_CONF%\keystore.p12" >nul
	echo dev-tls-setup: copied keystore to dist\container-home\conf\
)

echo dev-tls-setup: done. Run start-tls.bat after ant assemble-container.
exit /b 0
