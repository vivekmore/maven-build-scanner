@echo off

@REM set title of command window
title %0

@setlocal

set ERROR_CODE=0

@REM set -eu
cd /D "%~dp0"

@REM ------ OLDPWD ----------------
set OLDPWD=%CD%
@REM if '%*'=='' cd & exit /b
@REM if '%*'=='-' (
@REM     cd /d %OLDPWD%
@REM     set OLDPWD=%cd%
@REM ) else (
@REM     cd /d %*
@REM     if not errorlevel 1 set OLDPWD=%cd%
@REM )
@REM ------ OLDPWD ----------------


call :status "1\5 Starting local Mongo container"
if not exist "db" mkdir "db"

@REM docker ps -q -f name=build_scan_db
for /f %%i in ('docker ps -q -f name=build_scan_db') do set DOCKER_BUILD_EXISTS=%%i
echo %DOCKER_BUILD_EXISTS%

if not "%DOCKER_BUILD_EXISTS%" == "" goto init
docker run -d -v "$(pwd)"/db:/data/db -P -p 27017:27017 --rm --name build_scan_db mongo

:init

call :status "2\5 Building Maven extension"
mvn install -q

for /f %%i in ('mvn help:evaluate -Dexpression=maven.home -DforceStdout -q') do set MAVEN_HOME=%%i
COPY target\maven-build-scanner-1.0.0-SNAPSHOT-jar-with-dependencies.jar "%MAVEN_HOME%"\lib\ext\
cd server

call :status "3\5 Building web server"
npm update
npm install

call :status "4\5 Starting web server"
npm start
cd %OLDPWD%

call :status "5\5 Creating your first scan"
env MAVEN_BUILD_SCANNER=1 mvn verify -q -DskipTests

call :status "Ready"
echo "To perform a scan, run:"
echo
echo "  env MAVEN_BUILD_SCANNER=1 mvn <goals>"
echo
echo "Open http:/\localhost:3000 to see scan results."

@endlocal & set ERROR_CODE=%ERROR_CODE%

:status
echo ">>> %~1 <<<"
EXIT /B 0

exit /B %ERROR_CODE%
