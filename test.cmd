@echo off

@setlocal

@REM docker version
docker ps -q -f name=build_scan_db
for /f %%i in ('docker ps') do set DOCKER_BUILD_EXISTS=%%i
echo %DOCKER_BUILD_EXISTS%
for /f %%i in ('docker ps -q -f name^=build_scan_db') do set DOCKER_BUILD_EXISTS1=%%i
echo %DOCKER_BUILD_EXISTS1%

for /f %%i in ('mvn help:evaluate -Dexpression^=maven.home -DforceStdout -q') do set MAVEN_HOME=%%i
echo %MAVEN_HOME%
@endlocal
