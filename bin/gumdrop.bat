@echo off
rem Gumdrop servlet container launcher (lib/ distribution layout).
rem Requires Java 25+. Set GUMDROP_HOME to the install root if this script is relocated.

if "%GUMDROP_HOME%"=="" (
	for %%I in ("%~dp0..") do set "GUMDROP_HOME=%%~fI"
)

if "%JAVA%"=="" set "JAVA=java"

set "BOOTSTRAP=%GUMDROP_HOME%\lib\gumdrop-bootstrap.jar"
if not exist "%BOOTSTRAP%" (
	echo gumdrop: missing %BOOTSTRAP% ^(run ant assemble-container or unpack the distribution zip^) 1>&2
	exit /b 1
)

if "%MAX_RAM_PERCENTAGE%"=="" set "MAX_RAM_PERCENTAGE=75.0"
if "%LOGGING_PROPERTIES%"=="" set "LOGGING_PROPERTIES=%GUMDROP_HOME%\logging.properties"

set "JVM_OPTS=-XX:MaxRAMPercentage=%MAX_RAM_PERCENTAGE%"

set "LOGGING="
if exist "%LOGGING_PROPERTIES%" (
	set "LOGGING=-Djava.util.logging.config.file=%LOGGING_PROPERTIES%"
)

"%JAVA%" %JVM_OPTS% %LOGGING% %JAVA_OPTS% -cp "%BOOTSTRAP%" org.bluezoo.gumdrop.Bootstrap %*
exit /b %ERRORLEVEL%
