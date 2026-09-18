@echo off
rem Gumdrop launcher (Windows). Requires Java 25+.
rem Prefers the lib/ distribution (GUMDROP_HOME\bin\gumdrop.bat); falls back to the fat jar.
rem Environment: JAVA, GUMDROP_HOME, GUMDROP_JAR, LOGGING_PROPERTIES, JAVA_OPTS, MAX_RAM_PERCENTAGE

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

if defined GUMDROP_HOME if exist "%GUMDROP_HOME%\bin\gumdrop.bat" (
	call "%GUMDROP_HOME%\bin\gumdrop.bat" %*
	exit /b %ERRORLEVEL%
)

if not defined JAVA set "JAVA=java"
if not defined GUMDROP_JAR set "GUMDROP_JAR=%SCRIPT_DIR%\dist\gumdrop-container.jar"
if not defined LOGGING_PROPERTIES set "LOGGING_PROPERTIES=%SCRIPT_DIR%\logging.properties"
if not defined MAX_RAM_PERCENTAGE set "MAX_RAM_PERCENTAGE=75.0"

set "JVM_OPTS=-XX:MaxRAMPercentage=%MAX_RAM_PERCENTAGE%"
set "LOGGING="
if defined LOGGING_PROPERTIES if exist "%LOGGING_PROPERTIES%" (
	set "LOGGING=-Djava.util.logging.config.file=%LOGGING_PROPERTIES%"
)

"%JAVA%" %JVM_OPTS% %LOGGING% %JAVA_OPTS% -jar "%GUMDROP_JAR%" %*
exit /b %ERRORLEVEL%
