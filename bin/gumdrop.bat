@echo off
rem Gumdrop servlet container launcher (lib/ distribution layout).
rem Set GUMDROP_HOME to the install root if this script is relocated.

if "%GUMDROP_HOME%"=="" (
	for %%I in ("%~dp0..") do set "GUMDROP_HOME=%%~fI"
)

set "JAVA=%JAVA%"
if "%JAVA%"=="" set "JAVA=java"

set "BOOTSTRAP=%GUMDROP_HOME%\lib\gumdrop-bootstrap.jar"
if not exist "%BOOTSTRAP%" (
	echo gumdrop: missing %BOOTSTRAP% >&2
	exit /b 1
)

if "%MAX_RAM_PERCENTAGE%"=="" set "MAX_RAM_PERCENTAGE=75.0"

set "JVM_OPTS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=%MAX_RAM_PERCENTAGE%"

if not "%LOGGING_PROPERTIES%"=="" if exist "%LOGGING_PROPERTIES%" (
	set "LOGGING=-Djava.util.logging.config.file=%LOGGING_PROPERTIES%"
)

"%JAVA%" %JVM_OPTS% %LOGGING% %JAVA_OPTS% -cp "%BOOTSTRAP%" org.bluezoo.gumdrop.Bootstrap %*
