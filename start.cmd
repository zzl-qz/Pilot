@echo off
setlocal

pushd "%~dp0" || exit /b 1
set "PILOT_START_JAVA=java"
if defined JAVA_HOME set "PILOT_START_JAVA=%JAVA_HOME%\bin\java.exe"

set "PILOT_START_SPEC="
for /f "tokens=1,2,3" %%A in ('""%PILOT_START_JAVA%" -XshowSettings:properties -version 2^>^&1"') do if "%%A"=="java.specification.version" set "PILOT_START_SPEC=%%C"
if not defined PILOT_START_SPEC goto java_unsupported
set "PILOT_START_MAJOR=%PILOT_START_SPEC%"
if "%PILOT_START_SPEC:~0,2%"=="1." set "PILOT_START_MAJOR=%PILOT_START_SPEC:~2%"
for /f "tokens=1 delims=." %%V in ("%PILOT_START_MAJOR%") do set "PILOT_START_MAJOR=%%V"
for /f "delims=0123456789" %%V in ("%PILOT_START_MAJOR%") do goto java_unsupported
if %PILOT_START_MAJOR% LSS 25 goto java_unsupported

rem Install reactor dependencies and run tests before starting the application.
call mvnw.cmd -pl pilot-server -am install
set "PILOT_START_EXIT=%ERRORLEVEL%"
if not "%PILOT_START_EXIT%"=="0" goto finish

"%PILOT_START_JAVA%" --enable-native-access=ALL-UNNAMED -jar pilot-server\target\pilot-server-0.0.1-SNAPSHOT.jar %*
set "PILOT_START_EXIT=%ERRORLEVEL%"

:finish
popd
exit /b %PILOT_START_EXIT%

:java_unsupported
if not defined PILOT_START_SPEC set "PILOT_START_SPEC=unavailable"
echo Pilot requires JDK 25 or newer. Detected Java %PILOT_START_SPEC% via "%PILOT_START_JAVA%". 1>&2
echo Set JAVA_HOME or PATH to a suitable JDK before starting. 1>&2
set "PILOT_START_EXIT=1"
goto finish
